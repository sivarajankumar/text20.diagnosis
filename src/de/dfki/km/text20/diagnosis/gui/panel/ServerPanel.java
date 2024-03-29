/*
 * ServerPanel.java
 *
 * Copyright (c) 2010, Ralf Biedert, DFKI. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301  USA
 *
 */
package de.dfki.km.text20.diagnosis.gui.panel;

import java.awt.Component;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JSlider;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import org.jdesktop.swingx.JXHyperlink;

import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;

import de.dfki.km.text20.diagnosis.gui.MiniWindow;
import de.dfki.km.text20.diagnosis.gui.RecalibrationWindow;
import de.dfki.km.text20.diagnosis.gui.components.BrainDataChart;
import de.dfki.km.text20.diagnosis.gui.components.EyeDistanceChart;
import de.dfki.km.text20.diagnosis.gui.components.EyePositionChart;
import de.dfki.km.text20.diagnosis.gui.components.JStatusIndicator;
import de.dfki.km.text20.diagnosis.gui.components.PupilSizeChart;
import de.dfki.km.text20.diagnosis.gui.panel.templates.ServerPanelTemplate;
import de.dfki.km.text20.diagnosis.model.ApplicationData;
import de.dfki.km.text20.diagnosis.model.ServerInfo;
import de.dfki.km.text20.diagnosis.util.BrainTrackingEventRingbuffer;
import de.dfki.km.text20.diagnosis.util.EyeTrackingEventEvaluator;
import de.dfki.km.text20.diagnosis.util.EyeTrackingEventEvaluator.DataPartition;
import de.dfki.km.text20.diagnosis.util.EyeTrackingEventEvaluator.DiagState;
import de.dfki.km.text20.diagnosis.util.EyeTrackingEventRingbuffer;
import de.dfki.km.text20.services.trackingdevices.brain.BrainTrackingDeviceInfo;
import de.dfki.km.text20.services.trackingdevices.brain.BrainTrackingEvent;
import de.dfki.km.text20.services.trackingdevices.common.TrackingEvent;
import de.dfki.km.text20.services.trackingdevices.common.TrackingListener;
import de.dfki.km.text20.services.trackingdevices.eyes.EyeTrackingEvent;
import de.dfki.km.text20.trackingserver.eyes.remote.TrackingCommand;

/**
 * @author rb, ABu
 *
 */
public class ServerPanel extends ServerPanelTemplate implements TrackingListener<TrackingEvent> {

    /** */
    private static final long serialVersionUID = -7647208759232985123L;

    /** */
    private final ApplicationData applicationData;

    /** */
    private final ServerInfo serverInfo;

    /** */
    final HashMap<String, Boolean> channelStatus = new HashMap<String, Boolean>(10);

    /** */
    private boolean listenToChannels = true;


    /** */
    final EyeTrackingEventRingbuffer eyeTrackingRingBuffer;

    /** */
    final BrainTrackingEventRingbuffer brainTrackingRingBuffer;


    /** Approximated average eye tracking event rate in [events / seconds] */
    int avgEyeTrackingEventRate = 1;

    /** Approximated average brain tracking event rate in [events / seconds] */
    int avgBrainTrackingEventRate = 1;

    /** Current eye tracking event rate */
    int eyeTrackingEventRate = 1;

    /** Current brain tracking event rate */
    int brainTrackingEventRate = 1;

    /** Counter for the incoming eye tracking events */
    int eyeTrackingEventCounter = 0;

    /** Counter for the incoming brain tracking events */
    int brainTrackingEventCounter = 0;


    /** */
    PupilSizeChart pupilSizeHistory;

    /** */
    EyeDistanceChart headDistanceHistory;

    /** */
    EyePositionChart headPositionHistory;

    /** */
    BrainDataChart brainHistory;

    /** */
    RecalibrationWindow recalibrationWindow;

    /** */
    MiniWindow miniWindow;


    /** Common listener to handle all hyperlink commands */
    private final ActionListener commandProcessor = new CommandListener();

    private final ItemListener checkBoxProcessor = new CheckBoxListener();

    private final ChangeListener bufferSliderProcessor = new SliderChangedListener();

    private final String[] statusLabels = { "Bad", "Vague", "Ok", "Off", "On" };

    /**
     * @param applicationData
     * @param serverInfo
     */
    public ServerPanel(final ApplicationData applicationData, final ServerInfo serverInfo) {
        super();

        this.applicationData = applicationData;
        this.serverInfo = serverInfo;

        this.eyeTrackingRingBuffer = serverInfo.getEyeTrackingRingBuffer();
        this.eyeTrackingRingBuffer.setRingbufferSize(1200);
        this.eyeTrackingRingBuffer.restartRecording();

        this.brainTrackingRingBuffer = serverInfo.getBrainTrackingRingBuffer();
        this.brainTrackingRingBuffer.setRingbufferSize(1200);
        this.brainTrackingRingBuffer.restartRecording();

        setGUIParameters();

        new Timer().scheduleAtFixedRate(new AverageEventRateEstimator(), 1000, 1000);
    }

    /** */
    private void setGUIParameters() {
        // Setting up eye tracking components if device is available
        if (this.serverInfo.getEyeTrackingDevice() != null) {
            this.eyeTrackingDeviceNameValueLabel.setText(this.serverInfo.getEyeTrackingDevice().getDeviceInfo().getInfo("DEVICE_NAME"));
            this.eyeTrackingDeviceTypeValueLabel.setText(this.serverInfo.getEyeTrackingDevice().getDeviceType().name());
            this.eyeTrackingDeviceLocationValueLabel.setText(this.serverInfo.getURI());

            // Starting recording for eye tracker
            this.serverInfo.getEyeTrackingRingBuffer().setRecording(true);
            this.recordIndicator.setStatus(DiagState.ON);
            this.recordSwitch.setText(this.statusLabels[this.recordIndicator.getStatus().ordinal()]);

            // Setting application data and server info for top eye visualisation displays
            this.eyePositionDisplay.setApplicationData(this.applicationData);
            this.eyePositionDisplay.setServerInfo(this.serverInfo);

            this.eyeDistanceDisplay.setApplicationData(this.applicationData);
            this.eyeDistanceDisplay.setServerInfo(this.serverInfo);

            this.pupilSizeDisplay.setApplicationData(this.applicationData);
            this.pupilSizeDisplay.setServerInfo(this.serverInfo);

            // Creating eye tracking charts
            this.pupilSizeHistory = new PupilSizeChart(this.applicationData, this.serverInfo);
            this.headDistanceHistory = new EyeDistanceChart(this.applicationData, this.serverInfo);
            this.headPositionHistory = new EyePositionChart(this.applicationData, this.serverInfo);
            this.recalibrationWindow = new RecalibrationWindow(this.applicationData, this.serverInfo);

            // Adding listeners for eye tracking components
            this.recordSwitch.addActionListener(this.commandProcessor);
            this.gazePositionHistoryLink.addActionListener(this.commandProcessor);
            this.headDistanceHistoryLink.addActionListener(this.commandProcessor);
            this.headPositionHistoryLink.addActionListener(this.commandProcessor);
            this.pupilSizeHistoryLink.addActionListener(this.commandProcessor);
            this.performRecalibrationLink.addActionListener(this.commandProcessor);
            this.performHardwareRecalibrationLink.addActionListener(this.commandProcessor);

            this.recalibrationWindow.addWindowListener(new WindowAdapter() {

                @Override
                public void windowActivated(final WindowEvent e) {
                    ((JFrame) ServerPanel.this.getRootPane().getParent()).setVisible(false);
                }

                @Override
                public void windowDeactivated(final WindowEvent e) {
                    ((JFrame) ServerPanel.this.getRootPane().getParent()).setVisible(true);
                }

            });

            this.calibrationIndicator.setStatus(DiagState.VAGUE);

            this.bufferSizeSlider.addChangeListener(this.bufferSliderProcessor);
        }



        // Setting up brain tracking components if device is available
        if (this.serverInfo.getBrainTrackingDevice() != null) {
            this.brainTrackingDeviceNameValueLabel.setText(this.serverInfo.getBrainTrackingDevice().getDeviceInfo().getInfo("DEVICE_NAME"));
            this.brainTrackingDeviceLocationValueLabel.setText(this.serverInfo.getURI());

            // Starting recording for brain tracker
            this.serverInfo.getBrainTrackingRingBuffer().setRecording(true);
            this.brainRecordIndicator.setStatus(DiagState.ON);
            this.brainTrackingRecordSwitch.setText(this.statusLabels[this.brainRecordIndicator.getStatus().ordinal()]);

            // Creating brain tracking charts
            this.brainHistory = new BrainDataChart(this.applicationData, this.serverInfo, this.channelStatus);

            this.brainTrackingRecordSwitch.addActionListener(this.commandProcessor);
            this.brainHistoryLink.addActionListener(this.commandProcessor);

            this.bufferSizeBrainTrackerHistorySlider.addChangeListener(this.bufferSliderProcessor);
        }

        this.trackingSince.setText(new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date()));

        this.miniWindow = new MiniWindow(this.applicationData, this.serverInfo);

        // Show / Hide mini window
        this.ministatus.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ServerPanel.this.miniWindow.setVisible(ServerPanel.this.ministatus.isSelected());
            }
        });

        updateUI();
    }

    /* (non-Javadoc)
     * @see de.dfki.km.augmentedtext.services.trackingdevices.TrackingListener#newEyeTrackingEvent(de.dfki.km.augmentedtext.services.trackingdevices.EyeTrackingEvent)
     */
    @Override
    public void newTrackingEvent(TrackingEvent event) {
        if (event instanceof EyeTrackingEvent) {
            this.eyeTrackingEventCounter++;
            this.eyeTrackingRingBuffer.addEvent((EyeTrackingEvent) event);

            evaluateEvent((EyeTrackingEvent) event);

            // fixed widgets
            this.eyePositionDisplay.newTrackingEvent(event);
            this.eyeDistanceDisplay.newTrackingEvent(event);
            this.pupilSizeDisplay.newTrackingEvent(event);

            // on demand windows
            this.pupilSizeHistory.newTrackingEvent(event);
            this.headDistanceHistory.newTrackingEvent(event);
            this.headPositionHistory.newTrackingEvent(event);

            this.recalibrationWindow.newEyeTrackingEvent((EyeTrackingEvent) event);
            this.miniWindow.newEyeTrackingEvent((EyeTrackingEvent) event);
        }

        if (event instanceof BrainTrackingEvent) {
            this.brainTrackingEventCounter++;
            this.brainTrackingRingBuffer.addEvent((BrainTrackingEvent) event);

            this.brainHistory.newTrackingEvent(event);

            // Listening once to the channels right at the beginning and setting up the channels on the ChannelPanel
            if (this.listenToChannels) {
                setBrainTrackingChannelGUIComponents((BrainTrackingEvent) event);
                this.listenToChannels = false;
            }
        }
    }

    /**
     * @param event
     */
    protected void evaluateEvent(final EyeTrackingEvent event) {
        //TODO Reactive Tray ?
        //        final DiagState newTrayState = EyeTrackingEventEvaluator.evaluateEvent(event);

        final Map<DataPartition, DiagState> qualityStatus = EyeTrackingEventEvaluator.getOverallQualityStatus(event);

        final DiagState headPositionState = qualityStatus.get(DataPartition.HEAD_POSITION);
        this.getHeadPositionIndicator().setStatus(qualityStatus.get(DataPartition.HEAD_POSITION));

        final DiagState headDistanceState = qualityStatus.get(DataPartition.HEAD_DISTANCE);
        this.getHeadDistanceIndicator().setStatus(headDistanceState);

        final DiagState eventRateState = (DiagState) this.getEventRateIndicator().getStatus();
        final DiagState overallState = EyeTrackingEventEvaluator.getWorstState(headDistanceState, headPositionState, eventRateState);

        this.getOverallQualityIndicator().setStatus(overallState);
        this.getOverallQualityLabel().setText(this.statusLabels[overallState.ordinal()]);

        //        final ServerDiagnosisSystemTray tray = ServerPanel.this.applicationData.getTray();
        //        if (tray.getCurrentTrayState() != overallState) {
        //            tray.setTrayIcon(overallState);
        //        }
    }

    /**
     * @param event
     * Checks a given BrainTrackingEvent for its channels and constructs the layout in the ChannelPanel.
     * For every channel found a checkbox is constructed.
     */
    private void setBrainTrackingChannelGUIComponents(final BrainTrackingEvent event) {
        BrainTrackingDeviceInfo info = this.serverInfo.getBrainTrackingDevice().getDeviceInfo();
        
        // Getting the number of channels found in the event
        final int channelNumber = info.getChannelNames().size();

        // Constraints needed for the correct gui layout
        final CellConstraints cc = new CellConstraints();

        // Clearing the checkbox stati just in case
        this.channelStatus.clear();

        // Defining the new specifications for the Formlayout, i.e. the number of rows depending on the channelNumber
        final String encodedColumnSpecs = "default:grow";
        final String encodedRowSpecs = channelNumber + "*(default)";
        this.ChannelPanel.setLayout(new FormLayout(encodedColumnSpecs, encodedRowSpecs));

        // Construction of the checkboxes
        int columnIndex = 1;
        for (String channelName : info.getChannelNames()) {
            final JCheckBox checkBox = new JCheckBox(channelName, true);
            checkBox.addItemListener(this.checkBoxProcessor);

            // Adding checkbox status to the map for later access
            this.channelStatus.put(channelName, Boolean.TRUE);

            // Adding checkbox to the gui layout at the correct column position
            this.ChannelPanel.add(checkBox, cc.xy(1, columnIndex));

            columnIndex++;
        }
    }


    /**
     * @author buhl
     * ActionListener class to process all panel events
     */
    class CommandListener implements ActionListener {

        @SuppressWarnings("synthetic-access")
        @Override
        public void actionPerformed(final ActionEvent e) {

            // process all hyperlink commands

            if (e.getSource() instanceof JXHyperlink) {
                final JXHyperlink hyperlink = (JXHyperlink) e.getSource();

                if (hyperlink.getActionCommand() == "openPupilSizeHistory") {
                    showHistoryFrame(ServerPanel.this.pupilSizeHistory, "Pupil Size History");
                }

                if (hyperlink.getActionCommand() == "openGazePositionHistory") {
                    // TODO GazePositionHistory implementation
                }

                if (hyperlink.getActionCommand() == "openHeadPositionHistory") {
                    showHistoryFrame(ServerPanel.this.headPositionHistory, "Head Position History");
                }

                if (hyperlink.getActionCommand() == "openHeadDistanceHistory") {
                    showHistoryFrame(ServerPanel.this.headDistanceHistory, "Head Distance History");
                }

                if (hyperlink.getActionCommand() == "toggleRecording") {
                    final EyeTrackingEventRingbuffer rb = ServerPanel.this.serverInfo.getEyeTrackingRingBuffer();
                    final JStatusIndicator si = ServerPanel.this.recordIndicator;

                    rb.setRecording(!rb.isRecording());
                    si.setStatus(rb.isRecording() ? DiagState.ON : DiagState.OFF);

                    ServerPanel.this.recordSwitch.setText(ServerPanel.this.statusLabels[si.getStatus().ordinal()]);
                }

                if (hyperlink.getActionCommand() == "toggleBrainRecording") {
                    final BrainTrackingEventRingbuffer rb = ServerPanel.this.serverInfo.getBrainTrackingRingBuffer();
                    final JStatusIndicator si = ServerPanel.this.brainRecordIndicator;

                    rb.setRecording(!rb.isRecording());
                    si.setStatus(rb.isRecording() ? DiagState.ON : DiagState.OFF);

                    ServerPanel.this.brainTrackingRecordSwitch.setText(ServerPanel.this.statusLabels[si.getStatus().ordinal()]);
                }

                if (hyperlink.getActionCommand() == "openBrainHistory") {
                    showHistoryFrame(ServerPanel.this.brainHistory, "Brain Data History");
                }

                if (hyperlink.getActionCommand() == "performRecalibration") {
                    hideEverythingElse();

                    ServerPanel.this.getCalibrationIndicator().setStatus(DiagState.OK);
                    ServerPanel.this.recalibrationWindow.setTransparency(ServerPanel.this.transparentRecalibration.isSelected());
                    ServerPanel.this.recalibrationWindow.setVisible(true);
                    ServerPanel.this.recalibrationWindow.setServerInfo(ServerPanel.this.serverInfo);
                }

                if (hyperlink.getActionCommand() == "performHardwareRecalibration") {
                    hideEverythingElse();
                    ServerPanel.this.serverInfo.getEyeTrackingDevice().sendLowLevelCommand(TrackingCommand.HARDWARE_CALIBRATION);
                }
            }
        }

        /**
         * @param chart
         * @param title
         */
        private void showHistoryFrame(final Component chart, final String title) {
            final JFrame frame = new JFrame();
            frame.setTitle(title);
            frame.add(chart);
            frame.pack();
            frame.setVisible(true);
        }


        /**
         * Hide everything else ...
         */
        private void hideEverythingElse() {
            @SuppressWarnings("synthetic-access")
            final Collection<ServerInfo> serverInfos = ServerPanel.this.applicationData.getServerInfos();

            for (ServerInfo info : serverInfos) {
                info.getMainWindow().setState(Frame.ICONIFIED);
            }
        }
    }


    /**
     * @author Vartan
     * This task estimates the current eye and brain tracking event rate and also sets the values in the coresponding gui elements.
     * For the calculation the number of events is counted between two timestamps.
     */
    class AverageEventRateEstimator extends TimerTask {
        private long previousTimeStamp = System.currentTimeMillis();
        private long currentTimeStamp = 0;

        private final int averageEyeTrackingEventRate = ServerPanel.this.avgEyeTrackingEventRate;
        private final int averageBrainTrackingEventRate = ServerPanel.this.avgBrainTrackingEventRate;

        private boolean listenFlag = true;
        private int counter = 0;

        @Override
        public void run() {
            this.currentTimeStamp = System.currentTimeMillis();

            final int delta = (int) Math.max((this.currentTimeStamp - this.previousTimeStamp) / 1000, 1);

            // Calculating event rates
            ServerPanel.this.eyeTrackingEventRate = ServerPanel.this.eyeTrackingEventCounter / delta;
            ServerPanel.this.brainTrackingEventRate = ServerPanel.this.brainTrackingEventCounter / delta;

            // Wait some interations/seconds and set the average buffer size and event rate for brain and eye tracking
            if (this.counter > 5) {
                if (this.listenFlag) {
                    // Eye tracker buffer size setup
                    ServerPanel.this.eyeTrackingRingBuffer.setRingbufferSize(ServerPanel.this.bufferSizeSlider.getValue() * ServerPanel.this.eyeTrackingEventRate * 2);
                    ServerPanel.this.recordingTitle.setText("Eye Tracking Recording Status (" + ServerPanel.this.eyeTrackingRingBuffer.size() + " Events)");
                    ServerPanel.this.avgEyeTrackingEventRate = ServerPanel.this.eyeTrackingEventRate;

                    // Brain tracker buffer size setup
                    ServerPanel.this.brainTrackingRingBuffer.setRingbufferSize(ServerPanel.this.bufferSizeBrainTrackerHistorySlider.getValue() * ServerPanel.this.brainTrackingEventRate * 2);
                    ServerPanel.this.brainTrackerRecordingStatusLabel.setText("Brain Tracking Recording Status (" + ServerPanel.this.brainTrackingRingBuffer.size() + " Events)");
                    ServerPanel.this.avgBrainTrackingEventRate = ServerPanel.this.brainTrackingEventRate;

                    this.listenFlag = false;
                }
            } else {
                this.counter++;
            }


            // Getting and setting the eye tracking event rate + event rate indicator
            ServerPanel.this.getEventRate().setText("" + ServerPanel.this.eyeTrackingEventRate);
            ServerPanel.this.getEventRateIndicator().setStatus((ServerPanel.this.eyeTrackingEventRate > this.averageEyeTrackingEventRate) ? DiagState.OK : DiagState.BAD);


            ServerPanel.this.getBrainTrackingEventRateValue().setText("" + ServerPanel.this.brainTrackingEventRate);
            ServerPanel.this.getBrainTrackingEventRateIndicator().setStatus((ServerPanel.this.brainTrackingEventRate > this.averageBrainTrackingEventRate) ? DiagState.OK : DiagState.BAD);

            // Resetting values for the next calculation iteration
            this.previousTimeStamp = this.currentTimeStamp;
            ServerPanel.this.eyeTrackingEventCounter = 0;
            ServerPanel.this.brainTrackingEventCounter = 0;
        }
    }


    /**
     * @author Vartan
     * This listener watches currently over the checkboxes and stores their status in a hashmap for later use by other objects
     */
    class CheckBoxListener implements ItemListener {

        @Override
        public void itemStateChanged(ItemEvent e) {
            // Getting the the source checkbox which was checked/unchecked
            Object source = e.getItemSelectable();

            if (source instanceof JCheckBox) {
                // Getting the name which equals the channel name and it state
                final String channelName = ((JCheckBox) source).getText();
                final Boolean channelDisplay = (e.getStateChange() == ItemEvent.SELECTED) ? Boolean.TRUE : Boolean.FALSE;

                // Storing the value in the hashmap
                ServerPanel.this.channelStatus.put(channelName, channelDisplay);
            }
        }
    }


    /**
     * @author Vartan
     * One change listener for the sliders
     */
    class SliderChangedListener implements ChangeListener {

        @Override
        public void stateChanged(ChangeEvent e) {
            final int sliderValue = ((JSlider) e.getSource()).getValue();

            if (e.getSource() == ServerPanel.this.bufferSizeSlider) {
                ServerPanel.this.bufferSizeLabel.setText(sliderValue + "s");
                ServerPanel.this.eyeTrackingRingBuffer.setRingbufferSize(sliderValue * ServerPanel.this.eyeTrackingEventRate * 2);
                ServerPanel.this.recordingTitle.setText("Eye Tracking Recording Status (" + ServerPanel.this.eyeTrackingRingBuffer.size() + " Events)");
            }

            if (e.getSource() == ServerPanel.this.bufferSizeBrainTrackerHistorySlider) {
                ServerPanel.this.bufferSizeBrainTrackerHistoryValueLabel.setText(sliderValue + "s");
                ServerPanel.this.brainTrackingRingBuffer.setRingbufferSize(sliderValue * ServerPanel.this.brainTrackingEventRate * 2);
                ServerPanel.this.brainTrackerRecordingStatusLabel.setText("Brain Tracking Recording Status (" + ServerPanel.this.brainTrackingRingBuffer.size() + " Events)");
            }
        }
    }
}