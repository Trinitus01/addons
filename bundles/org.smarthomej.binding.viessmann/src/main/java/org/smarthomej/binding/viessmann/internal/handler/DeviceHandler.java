/**
 * Copyright (c) 2021 Contributors to the SmartHome/J project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.smarthomej.binding.viessmann.internal.handler;

import static org.smarthomej.binding.viessmann.internal.ViessmannBindingConstants.*;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smarthomej.binding.viessmann.internal.config.ThingsConfig;
import org.smarthomej.binding.viessmann.internal.dto.ThingMessageDTO;
import org.smarthomej.binding.viessmann.internal.dto.ViessmannMessage;
import org.smarthomej.binding.viessmann.internal.dto.features.FeatureCommands;
import org.smarthomej.binding.viessmann.internal.dto.features.FeatureDataDTO;
import org.smarthomej.binding.viessmann.internal.dto.features.FeatureProperties;
import org.smarthomej.binding.viessmann.internal.dto.schedule.DaySchedule;
import org.smarthomej.binding.viessmann.internal.dto.schedule.ScheduleDTO;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * The {@link DeviceHandler} is responsible for handling DeviceHandler
 *
 *
 * @author Ronny Grun - Initial contribution
 */
@NonNullByDefault
public class DeviceHandler extends ViessmannThingHandler {

    private final Logger logger = LoggerFactory.getLogger(DeviceHandler.class);

    private static final Gson GSON = new GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss").create();

    private ThingsConfig config = new ThingsConfig();

    public DeviceHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        ThingsConfig config = getConfigAs(ThingsConfig.class);
        this.config = config;
        if (config.deviceId.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Invalid device id setting");
            return;
        }
        updateProperty(PROPERTY_ID, config.deviceId); // set representation property used by discovery

        setPollingDevice();

        initDeviceState();
        logger.trace("Device handler finished initializing");
    }

    @Override
    public void dispose() {
        unsetPollingDevice();
    }

    @Override
    public void initChannelState() {
        Bridge bridge = getBridge();
        ViessmannBridgeHandler bridgeHandler = bridge == null ? null : (ViessmannBridgeHandler) bridge.getHandler();
        if (bridgeHandler != null) {
            bridgeHandler.getAllFeaturesByDeviceId(config.deviceId);
        }
    }

    private void setPollingDevice() {
        Bridge bridge = getBridge();
        ViessmannBridgeHandler bridgeHandler = bridge == null ? null : (ViessmannBridgeHandler) bridge.getHandler();
        if (bridgeHandler != null) {
            bridgeHandler.setPollingDevice(config.deviceId);
        }
    }

    private void unsetPollingDevice() {
        Bridge bridge = getBridge();
        ViessmannBridgeHandler bridgeHandler = bridge == null ? null : (ViessmannBridgeHandler) bridge.getHandler();
        if (bridgeHandler != null) {
            bridgeHandler.unsetPollingDevice(config.deviceId);
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        try {
            Channel ch = thing.getChannel(channelUID.getId());
            if (ch != null) {
                logger.trace("ChannelUID: {}", ch.getProperties());
                Map<String, String> prop = ch.getProperties();
                String commands = prop.get("command");
                if (commands != null) {
                    String uri = null;
                    String param = null;
                    String com[] = commands.split(",");
                    if (OnOffType.ON.equals(command)) {
                        uri = prop.get("activateUri");
                        param = "{}";
                    } else if (OnOffType.OFF.equals(command)) {
                        uri = prop.get("deactivateUri");
                        param = "{}";
                    } else if (command instanceof DecimalType) {
                        logger.trace("Received DecimalType Command for Channel {}",
                                thing.getChannel(channelUID.getId()));
                    } else if (command instanceof QuantityType<?>) {
                        QuantityType<?> value = (QuantityType<?>) command;
                        Integer f = value.intValue();
                        String s = f.toString();
                        for (String str : com) {
                            if (str.indexOf("Temperature") != -1) {
                                uri = prop.get(str + "Uri");
                                param = "{\"" + prop.get(str + "Params") + "\":" + s + "}";
                                break;
                            }
                        }
                        logger.trace("Received QuantityType Command for Channel {} Comamnd: {}",
                                thing.getChannel(channelUID.getId()), value.floatValue());
                    } else if (command instanceof StringType) {
                        for (String str : com) {
                            String s = command.toString();
                            uri = prop.get(str + "Uri");
                            param = "{\"" + prop.get(str + "Params") + "\":" + s + "}";
                            break;
                        }
                        logger.trace("Received StringType Command for Channel {}",
                                thing.getChannel(channelUID.getId()));
                    }
                    if (uri != null && param != null) {
                        Bridge bridge = getBridge();
                        ViessmannBridgeHandler bridgeHandler = bridge == null ? null
                                : (ViessmannBridgeHandler) bridge.getHandler();
                        if (bridgeHandler != null) {
                            if (!bridgeHandler.setData(uri, param)) {
                                initChannelState();
                            }
                        }
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            logger.warn("handleCommand fails", e);
        }
    }

    @Override
    public void handleUpdateChannel(ViessmannMessage msg) {
        logger.trace("handleUpdateChannel: {}", msg);
    }

    @Override
    public void handleUpdate(FeatureDataDTO featureDataDTO) {
        logger.trace("Device handler received update: {}", featureDataDTO);
        ThingMessageDTO msg = new ThingMessageDTO();
        if (featureDataDTO.properties != null) {
            msg.setDeviceId(featureDataDTO.deviceId);
            msg.setFeatureClear(featureDataDTO.feature);
            msg.setFeatureDescription(getFeatureDescription(featureDataDTO.feature));
            FeatureCommands commands = featureDataDTO.commands;
            if (commands != null) {
                msg.setCommands(commands);
            }
            FeatureProperties prop = featureDataDTO.properties;
            List<String> entr = prop.getUsedEntries();
            if (!entr.isEmpty()) {
                for (String entry : entr) {
                    String valueEntry = "";
                    String typeEntry = "";
                    Boolean bool = false;
                    String featureName = getFeatureName(featureDataDTO.feature);
                    switch (entry) {
                        case "value":
                            msg.setFeatureName(featureName);
                            msg.setFeature(featureDataDTO.feature);
                            if (featureDataDTO.feature.indexOf("temperature") != -1) {
                                typeEntry = "temperature";
                            } else {
                                typeEntry = prop.value.type;
                            }
                            valueEntry = prop.value.value;
                            break;
                        case "status":
                            msg.setFeatureName(featureName + " status");
                            msg.setFeature(featureDataDTO.feature + "#status");
                            typeEntry = prop.status.type;
                            valueEntry = prop.status.value;
                            if ("off".equals(valueEntry)) {
                                typeEntry = "boolean";
                                bool = false;
                            } else if ("on".equals(valueEntry)) {
                                typeEntry = "boolean";
                                bool = true;
                            }
                            break;
                        case "active":
                            msg.setFeatureName(featureName + " active");
                            msg.setFeature(featureDataDTO.feature + "#active");
                            typeEntry = prop.active.type;
                            valueEntry = prop.active.value ? "true" : "false";
                            bool = prop.active.value;
                            break;
                        case "name":
                            msg.setFeatureName(featureName);
                            msg.setFeature(featureDataDTO.feature);
                            typeEntry = prop.name.type;
                            valueEntry = prop.name.value;
                            break;
                        case "shift":
                            msg.setFeatureName(featureName + " shift");
                            msg.setFeature(featureDataDTO.feature + "#shift");
                            typeEntry = prop.shift.type;
                            valueEntry = prop.shift.value.toString();
                            break;
                        case "slope":
                            msg.setFeatureName(featureName + " slope");
                            msg.setFeature(featureDataDTO.feature + "#slope");
                            typeEntry = prop.slope.type;
                            valueEntry = prop.slope.value.toString();
                            break;
                        case "entries":
                            msg.setFeatureName(featureName);
                            msg.setFeature(featureDataDTO.feature + "#schedule");
                            typeEntry = prop.entries.type.toString();
                            valueEntry = new Gson().toJson(prop.entries.value);
                            break;
                        case "overlapAllowed":
                            msg.setFeatureName(featureName);
                            msg.setFeature(featureDataDTO.feature + "#overlapAllowed");
                            typeEntry = prop.overlapAllowed.type;
                            valueEntry = prop.overlapAllowed.value ? "true" : "false";
                            bool = prop.overlapAllowed.value;
                            break;
                        case "temperature":
                            msg.setFeatureName(featureName + " temperature");
                            msg.setFeature(featureDataDTO.feature + "#temperature");
                            typeEntry = prop.temperature.type;
                            valueEntry = prop.temperature.value.toString();
                            typeEntry = "temperature";
                            break;
                        case "start":
                            msg.setFeatureName(featureName + " start");
                            msg.setFeature(featureDataDTO.feature + "#start");
                            typeEntry = prop.start.type;
                            valueEntry = prop.start.value;
                            break;
                        case "end":
                            msg.setFeatureName(featureName + " end");
                            msg.setFeature(featureDataDTO.feature + "#end");
                            typeEntry = prop.end.type;
                            valueEntry = prop.end.value;
                            break;
                        case "top":
                            msg.setFeatureName(featureName + " top");
                            msg.setFeature(featureDataDTO.feature + "#top");
                            typeEntry = prop.top.type;
                            valueEntry = prop.top.value.toString();
                            break;
                        case "middle":
                            msg.setFeatureName(featureName + " middle");
                            msg.setFeature(featureDataDTO.feature + "#middle");
                            typeEntry = prop.middle.type;
                            valueEntry = prop.middle.value.toString();
                            break;
                        case "bottom":
                            msg.setFeatureName(featureName + " bottom");
                            msg.setFeature(featureDataDTO.feature + "#bottom");
                            typeEntry = prop.bottom.type;
                            valueEntry = prop.bottom.value.toString();
                            break;
                        case "day":
                            msg.setFeatureName(featureName + " Day");
                            msg.setFeature(featureDataDTO.feature + "#day");
                            // returns array as string
                            typeEntry = prop.day.type;
                            valueEntry = prop.day.value.toString();
                            break;
                        case "week":
                            msg.setFeatureName(featureName + " Week");
                            msg.setFeature(featureDataDTO.feature + "#week");
                            // returns array as string
                            typeEntry = prop.week.type;
                            valueEntry = prop.week.value.toString();
                            break;
                        case "month":
                            msg.setFeatureName(featureName + " Month");
                            msg.setFeature(featureDataDTO.feature + "#month");
                            // returns array as string
                            typeEntry = prop.month.type;
                            valueEntry = prop.month.value.toString();
                            break;
                        case "year":
                            msg.setFeatureName(featureName + " Year");
                            msg.setFeature(featureDataDTO.feature + "#year");
                            // returns array as string
                            typeEntry = prop.year.type;
                            valueEntry = prop.year.value.toString();
                            break;
                        case "unit":
                            msg.setFeatureName(featureName + " unit");
                            msg.setFeature(featureDataDTO.feature + "#unit");
                            typeEntry = prop.unit.type;
                            valueEntry = prop.unit.value;
                            break;
                        case "starts":
                            msg.setFeatureName(featureName + " Starts");
                            msg.setFeature(featureDataDTO.feature + "#starts");
                            typeEntry = prop.starts.type;
                            valueEntry = prop.starts.value.toString();
                            break;
                        case "hours":
                            msg.setFeatureName(featureName + " Hours");
                            msg.setFeature(featureDataDTO.feature + "#hours");
                            typeEntry = prop.hours.type;
                            valueEntry = prop.hours.value.toString();
                            break;
                        default:
                            break;
                    }
                    msg.setType(typeEntry);
                    msg.setValue(valueEntry);
                    msg.setChannelType("type-" + typeEntry);
                    Boolean active = true;
                    if (msg.getDeviceId().indexOf(config.deviceId) != -1 && active) {
                        logger.trace("Feature: {} Type:{} Entry: {}={}", featureDataDTO.feature, typeEntry, entry,
                                valueEntry);
                        if (thing.getChannel(msg.getChannelId()) == null && !"unit".equals(entry)) {
                            createChannel(msg);
                        }
                        if (msg.getFeature().indexOf("#schedule") != -1) {
                            ThingMessageDTO subMsg = msg;
                            subMsg.setChannelType("type-boolean");
                            subMsg.setFeature(msg.getFeature().replace("#schedule", "#produced"));
                            subMsg.setFeatureName(getFeatureName(featureDataDTO.feature) + " produced");

                            if (thing.getChannel(subMsg.getChannelId()) == null && !"unit".equals(entry)) {
                                createSubChannel(subMsg);
                            }
                        }

                        if ("temperature".equals(typeEntry)) {
                            DecimalType state = DecimalType.valueOf(msg.getValue());
                            updateState(msg.getChannelId(), state);
                        } else if ("number".equals(typeEntry)) {
                            DecimalType state = DecimalType.valueOf(msg.getValue());
                            updateState(msg.getChannelId(), state);
                        } else if ("boolean".equals(typeEntry)) {
                            OnOffType state = bool ? OnOffType.ON : OnOffType.OFF;
                            updateState(msg.getChannelId(), state);
                        } else if ("string".equals(typeEntry) || "array".equals(typeEntry)) {
                            StringType state = StringType.valueOf(msg.getValue());
                            updateState(msg.getChannelId(), state);
                        } else if ("Schedule".equals(typeEntry)) {
                            StringType state = StringType.valueOf(msg.getValue());
                            updateState(msg.getChannelId(), state);
                            String channelId = msg.getChannelId().replace("#schedule", "#produced");
                            updateState(channelId, parseSchedule(msg.getValue()));
                        }
                    }
                }
            }
        }
    }

    /**
     * Creates new channels for the thing.
     *
     * @param msg contains everything is needed of the channel to be created.
     */
    private void createChannel(ThingMessageDTO msg) {
        ChannelUID channelUID = new ChannelUID(thing.getUID(), msg.getChannelId());
        ThingHandlerCallback callback = getCallback();
        if (callback == null) {
            logger.warn("Thing '{}'not initialized, could not get callback.", thing.getUID());
            return;
        }

        Map<String, String> prop = new HashMap<>();
        prop.put("feature", msg.getFeatureClear());
        String channelType = msg.getChannelType();

        FeatureCommands commands = msg.getCommands();
        if (commands != null) {
            List<String> com = commands.getUsedCommands();
            if (!com.isEmpty()) {
                for (String command : com) {
                    switch (command) {
                        case "setName":
                            channelType = msg.getChannelType();
                            prop.put("setNameUri", commands.setName.uri);
                            prop.put("command", "setName");
                            prop.put("setNameParams", "name");
                            break;
                        case "setCurve":
                            channelType = msg.getChannelType();
                            prop.put("setCurveUri", commands.setCurve.uri);
                            prop.put("command", "setCurve");
                            prop.put("setCurveParams", "slope,shift");
                            break;
                        case "setSchedule":
                            channelType = msg.getChannelType();
                            prop.put("setScheduleUri", commands.setSchedule.uri);
                            prop.put("command", "setSchedule");
                            prop.put("setScheduleParams", "newSchedule");
                            break;
                        case "setMode":
                            channelType = msg.getChannelType();
                            prop.put("setModeUri", commands.setMode.uri);
                            prop.put("command", "setMode");
                            prop.put("setModeParams", "mode");
                            break;
                        case "setTemperature":
                            if (!"type-boolean".equals(channelType)) {
                                channelType = "type-settemperature";
                            }
                            prop.put("setTemperatureUri", commands.setTemperature.uri);
                            prop.put("command", "setTemperature");
                            prop.put("setTemperatureParams", "targetTemperature");
                            break;
                        case "activate":
                            channelType = msg.getChannelType();
                            prop.put("activateUri", commands.activate.uri);
                            prop.put("command", "activate,deactivate");
                            prop.put("activateParams", "{}");
                            prop.put("deactivateParams", "{}");
                            break;
                        case "deactivate":
                            channelType = msg.getChannelType();
                            prop.put("deactivateUri", commands.deactivate.uri);
                            prop.put("command", "activate,deactivate");
                            prop.put("activateParams", "{}");
                            prop.put("deactivateParams", "{}");
                            break;
                        case "changeEndDate":
                            channelType = msg.getChannelType();
                            prop.put("changeEndDateUri", commands.changeEndDate.uri);
                            prop.put("command", "changeEndDate,schedule,unschedule");
                            prop.put("changeEndDatepParams", "end");
                            prop.put("scheduleParams", "start,end");
                            prop.put("unscheduleParams", "{}");
                            break;
                        case "schedule":
                            channelType = msg.getChannelType();
                            prop.put("scheduleUri", commands.schedule.uri);
                            prop.put("scheduleParams", "start,end");
                            break;
                        case "unschedule":
                            channelType = msg.getChannelType();
                            prop.put("unscheduleUri", commands.unschedule.uri);
                            prop.put("unscheduleParams", "{}");
                            break;
                        case "setTargetTemperature":
                            channelType = "type-setTargetTemperature";
                            prop.put("setTargetTemperatureUri", commands.setTargetTemperature.uri);
                            prop.put("command", "setTargetTemperature");
                            prop.put("setTargetTemperatureParams", "temperature");
                            break;
                        default:
                            break;
                    }
                }
            }
        }
        ChannelTypeUID channelTypeUID = new ChannelTypeUID(BINDING_ID, channelType);
        if (msg.getFeatureName().indexOf("active") != -1) {
            logger.trace("Feature: {} ChannelType: {}", msg.getFeatureClear(), channelType);
        }
        Channel channel = callback.createChannelBuilder(channelUID, channelTypeUID).withLabel(msg.getFeatureName())
                .withDescription(msg.getFeatureDescription()).withProperties(prop).build();
        updateThing(editThing().withoutChannel(channelUID).withChannel(channel).build());
    }

    /**
     * Creates new sub channels for the thing.
     *
     * @param msg contains everything is needed of the channel to be created.
     */
    private void createSubChannel(ThingMessageDTO msg) {
        ChannelUID channelUID = new ChannelUID(thing.getUID(), msg.getChannelId());
        ThingHandlerCallback callback = getCallback();
        if (callback == null) {
            logger.warn("Thing '{}'not initialized, could not get callback.", thing.getUID());
            return;
        }

        Map<String, String> prop = new HashMap<>();
        prop.put("feature", msg.getFeatureClear());
        String channelType = msg.getChannelType();

        ChannelTypeUID channelTypeUID = new ChannelTypeUID(BINDING_ID, channelType);
        if (msg.getFeatureName().indexOf("active") != -1) {
            logger.trace("Feature: {} ChannelType: {}", msg.getFeatureClear(), channelType);
        }
        Channel channel = callback.createChannelBuilder(channelUID, channelTypeUID).withLabel(msg.getFeatureName())
                .withDescription(msg.getFeatureDescription()).withProperties(prop).build();
        updateThing(editThing().withoutChannel(channelUID).withChannel(channel).build());
    }

    private String getFeatureName(String feature) {
        Pattern pattern = Pattern.compile("(\\.[0-3])");
        Matcher matcher = pattern.matcher(feature);
        if (matcher.find()) {
            String circuit = matcher.group(0);
            feature = matcher.replaceAll(".N");
            String name = FEATURES_MAP.getOrDefault(feature, feature) + " (Circuit: " + circuit.replace(".", "") + ")";
            return name;
        }
        return FEATURES_MAP.getOrDefault(feature, feature);
    }

    private @Nullable String getFeatureDescription(String feature) {
        feature.replaceAll("\\.[0-3]", ".N");
        return FEATURE_DESCRIPTION_MAP.get(feature);
    }

    private OnOffType parseSchedule(String scheduleJson) {
        Calendar now = Calendar.getInstance();

        int hour = now.get(Calendar.HOUR_OF_DAY); // Get hour in 24 hour format
        int minute = now.get(Calendar.MINUTE);

        Date currTime = parseTime(hour + ":" + minute);

        Date date = new Date();
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        int dayOfWeek = c.get(Calendar.DAY_OF_WEEK);

        ScheduleDTO schedule = GSON.fromJson(scheduleJson, ScheduleDTO.class);
        if (schedule != null) {
            List<DaySchedule> day = schedule.getMon();
            switch (dayOfWeek) {
                case 2:
                    day = schedule.getMon();
                    break;
                case 3:
                    day = schedule.getTue();
                    break;
                case 4:
                    day = schedule.getWed();
                    break;
                case 5:
                    day = schedule.getThu();
                    break;
                case 6:
                    day = schedule.getFri();
                    break;
                case 7:
                    day = schedule.getSat();
                    break;
                case 1:
                    day = schedule.getSun();
                    break;
                default:
                    break;
            }
            for (DaySchedule daySchedule : day) {
                Date startTime = parseTime(daySchedule.getStart());
                Date endTime = parseTime(daySchedule.getEnd());

                if (startTime.before(currTime) && endTime.after(currTime)) {
                    return OnOffType.ON;
                }
            }
        }
        return OnOffType.OFF;
    }

    private Date parseTime(String time) {
        final String inputFormat = "HH:mm";
        SimpleDateFormat inputParser = new SimpleDateFormat(inputFormat);
        try {
            return inputParser.parse(time);
        } catch (java.text.ParseException e) {
            return new Date(0);
        }
    }
}
