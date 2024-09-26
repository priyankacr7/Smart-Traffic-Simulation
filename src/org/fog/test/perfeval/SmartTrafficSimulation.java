package org.fog.test.perfeval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.Actuator;
import org.fog.entities.FogBroker;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.placement.Controller;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementEdgewards;
import org.fog.placement.ModulePlacementMapping;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;

/**
 * Simulation setup for testing scalability of iFogSim, of traffic light in SmartCity
 * Author: Priyanka Srivastava, Kanpur
 *
 */

public class SmartTrafficSimulation {
    static List<FogDevice> fogDevices = new ArrayList<>();
    static List<Sensor> sensors = new ArrayList<>();
    static List<Actuator> actuators = new ArrayList<>();

    /* Parameters setup */
    static boolean CLOUD = true; // Whether using a cloud-only architecture
    static int numOfIntersections = 2; // Number of intersections
    static int numOfCamerasPerIntersection = 2; // Cameras per intersection
    static int numOfVehicleSensorsPerIntersection = 2; // Sensors for vehicles & pedestrians
    static int numOfAirQualitySensorsPerIntersection = 1; // Sensors for air quality monitoring
    static double SENSOR_TRANSMISSION_TIME = 10; // ms

    public static void main(String[] args) {
        Log.printLine("Starting Smart Traffic Light Control...");

        try {
            Log.disable();

            /* CloudSim initialization */
            int numOfCloudUsers = 1;
            Calendar calendar = Calendar.getInstance();
            boolean traceFlag = false;
            CloudSim.init(numOfCloudUsers, calendar, traceFlag);

            /* Application setup */
            String appId = "smartTrafficLight";
            FogBroker fogBroker = new FogBroker("broker");
            Application application = createApplication(appId, fogBroker.getId());
            application.setUserId(fogBroker.getId());

            /* Create physical devices */
            createFogDevices(appId, fogBroker.getId());

            /* Create module mapping */
            ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();
            moduleMapping.addModuleToDevice("Monitor", "cloud"); // Add Monitor to the cloud

            for (FogDevice device : fogDevices) {
                if (device.getName().startsWith("intersection")) {
                    moduleMapping.addModuleToDevice("TrafficManager", device.getName()); // Add module to each intersection
                }
            }

            if (CLOUD) {
                moduleMapping.addModuleToDevice("TrafficAnalyzer", "cloud");
            } else {
                for (FogDevice device : fogDevices) {
                    if (device.getName().startsWith("intersection")) {
                        moduleMapping.addModuleToDevice("TrafficAnalyzer", device.getName());
                    }
                }
            }

            /* Create controller */
            Controller controller = new Controller("master-controller", fogDevices, sensors, actuators);
            controller.submitApplication(application, 0,
                    (CLOUD) ? (new ModulePlacementMapping(fogDevices, application, moduleMapping))
                            : (new ModulePlacementEdgewards(fogDevices, sensors, actuators, application, moduleMapping)));

            /* Start simulation */
            TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());
            CloudSim.startSimulation();
            CloudSim.stopSimulation();

            System.out.println("Simulation Complete");
            Log.printLine("Smart Traffic Light Control finished!");
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("SOMETHING WENT WRONG: " + e.getMessage());
            Log.printLine("Unwanted error happened: " + e.getMessage());
        }
    }

    private static Application createApplication(String appId, int fogBrokerId) {
        /* Create empty application */
        Application application = Application.createApplication(appId, fogBrokerId);

        /* Add modules to the application */
        application.addAppModule("TrafficManager", 10);
        application.addAppModule("TrafficAnalyzer", 10);
        application.addAppModule("Monitor", 10);

        /* Add edges between modules */
        application.addAppEdge("CAMERA", "TrafficManager", 4000, 500, "IMAGE_STREAM", Tuple.UP, AppEdge.SENSOR);
        application.addAppEdge("VEHICLE_SENSOR", "TrafficManager", 1000, 500, "VEHICLE_DETECTED", Tuple.UP, AppEdge.SENSOR);
        application.addAppEdge("AIR_QUALITY_SENSOR", "TrafficManager", 1000, 500, "AIR_QUALITY_DATA", Tuple.UP, AppEdge.SENSOR);
        application.addAppEdge("TrafficManager", "TrafficAnalyzer", 4500, 500, "TRAFFIC_DATA", Tuple.UP, AppEdge.MODULE);
        application.addAppEdge("TrafficAnalyzer", "TrafficManager", 1000, 500, "TRAFFIC_INSTRUCTION", Tuple.DOWN, AppEdge.MODULE);
        application.addAppEdge("TrafficManager", "TRAFFIC_LIGHT", 1000, 500, "TRAFFIC_UPDATE", Tuple.DOWN, AppEdge.ACTUATOR);

        /* Define input-output relationship for each module */
        application.addTupleMapping("TrafficManager", "CAMERA", "TRAFFIC_DATA", new FractionalSelectivity(1.0));
        application.addTupleMapping("TrafficAnalyzer", "TRAFFIC_DATA", "TRAFFIC_INSTRUCTION", new FractionalSelectivity(1.0));
        application.addTupleMapping("TrafficManager", "TRAFFIC_INSTRUCTION", "TRAFFIC_UPDATE", new FractionalSelectivity(1.0));

        /* Define application loops */
        final AppLoop loop1 = new AppLoop(Arrays.asList("CAMERA", "TrafficManager", "TrafficAnalyzer", "TrafficManager", "TRAFFIC_LIGHT"));
        List<AppLoop> loops = new ArrayList<>(Arrays.asList(loop1));
        application.setLoops(loops);

        return application;
    }

    private static void createFogDevices(String appId, int fogBrokerId) {
        /* Create cloud */
        FogDevice cloud = createStandardFogDevice("cloud", 44800, 40000, 100, 10000, 0, 0.0, 0.01, 16 * 103, 16 * 83.25);
        cloud.setParentId(-1);
        fogDevices.add(cloud);

        /* Create proxy server */
        FogDevice proxy = createStandardFogDevice("proxy", 2800, 4000, 10000, 10000, 1, 100.0, 0.0, 107.339, 83.4333);
        proxy.setParentId(cloud.getId());
        fogDevices.add(proxy);

        /* Create devices at intersections */
        for (int i = 0; i < numOfIntersections; ++i) {
            createIntersectionFogDevices(i + "", appId, fogBrokerId, proxy.getId());
        }
    }

    private static void createIntersectionFogDevices(String intersectionId, String appId, int fogBrokerId, int proxyId) {
        /* Create fog device for each intersection */
        String intersectionName = "intersection-" + intersectionId;
        FogDevice intersection = createCustomFogDevice(intersectionName, 2000, 4000, 10000, 10000, 2, 50.0, 0.01, 100.0, 90.0);
        intersection.setParentId(proxyId);

        /* Create cameras and sensors */
        for (int j = 0; j < numOfCamerasPerIntersection; ++j) {
            createCamera("camera-" + intersectionId + "-" + j, appId, fogBrokerId, intersection.getId());
        }
        for (int k = 0; k < numOfVehicleSensorsPerIntersection; ++k) {
            createSensor("vehicleSensor-" + intersectionId + "-" + k, appId, fogBrokerId, intersection.getId(), "VEHICLE_SENSOR");
        }
        // Add air quality sensors for environmental monitoring
        for (int l = 0; l < numOfAirQualitySensorsPerIntersection; ++l) {
            createSensor("airQualitySensor-" + intersectionId + "-" + l, appId, fogBrokerId, intersection.getId(), "AIR_QUALITY_SENSOR");
        }
    }


    private static FogDevice createStandardFogDevice(String deviceName, long mips, int ram, long upBw, long downBw, int level, double uplinkLatency, double ratePerMips, double busyPower, double idlePower) {
        // create PE with the specified MIPS
        List<Pe> peList = new ArrayList<Pe>();
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));

        int hostId = FogUtils.generateEntityId();
        long storage = 1000000; // host storage
        int bw = 10000; // bandwidth

        PowerHost host = new PowerHost(
                hostId,
                new RamProvisionerSimple(ram),
                new BwProvisionerOverbooking(bw),
                storage,
                peList,
                new StreamOperatorScheduler(peList),
                new FogLinearPowerModel(busyPower, idlePower)
        );

        List<Host> hostList = new ArrayList<Host>();
        hostList.add(host);

        FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
                "x86",
                "Linux",
                "Xen",
                host,
                10.0,
                3.0,
                0.05,
                0.001,
                0.0
        );

        FogDevice fogDevice = null;
        try {
            fogDevice = new FogDevice(deviceName, characteristics, new AppModuleAllocationPolicy(hostList), new LinkedList<Storage>(), 10, upBw, downBw, uplinkLatency, ratePerMips);
        } catch (Exception e) {
            e.printStackTrace();
        }

        fogDevice.setLevel(level);
        return fogDevice;
    }

    private static FogDevice createCustomFogDevice(String deviceName, long mips, int ram, long upBw, long downBw, int level, double uplinkLatency, double ratePerMips, double busyPower, double idlePower) {
        return createStandardFogDevice(deviceName, mips, ram, upBw, downBw, level, uplinkLatency, ratePerMips, busyPower, idlePower);
    }

    private static void createCamera(String cameraName, String appId, int userId, int parentId) {
        Sensor camera = new Sensor(cameraName, "CAMERA", userId, appId, new DeterministicDistribution(SENSOR_TRANSMISSION_TIME));
        sensors.add(camera);
        camera.setGatewayDeviceId(parentId);
        camera.setLatency(1.0); // latency of connection between camera and parent node
    }

//    private static void createSensor(String sensorName, String appId, int userId, int parentId, String airQualitySensor) {
//        Sensor sensor = new Sensor(sensorName, "SENSOR", userId, appId, new DeterministicDistribution(SENSOR_TRANSMISSION_TIME));
//        sensors.add(sensor);
//        sensor.setGatewayDeviceId(parentId);
//        sensor.setLatency(1.0); // latency of connection between sensor and parent node
//    }

    private static void createSensor(String sensorName, String appId, int userId, int parentId, String type) {
        Sensor sensor = new Sensor(sensorName, type, userId, appId, new DeterministicDistribution(SENSOR_TRANSMISSION_TIME));
        sensors.add(sensor);

        // Ensure the parentId is valid before setting it
        if (parentId >= 0) {
            sensor.setGatewayDeviceId(parentId);
            System.out.println("Created sensor: " + sensorName + " with gateway ID: " + parentId);
        } else {
            System.err.println("Error: Invalid parent ID for sensor: " + sensorName);
        }

        sensor.setLatency(1.0); // latency of connection between sensor and parent node
    }


    private static void createActuator(String actuatorName, String appId, int userId, int parentId) {
        Actuator actuator = new Actuator(actuatorName, userId, appId, "TRAFFIC_LIGHT");
        actuators.add(actuator);
        actuator.setGatewayDeviceId(parentId);
        actuator.setLatency(1.0); // latency of connection between actuator and parent node
    }


//    private static void createCamera(String name, String appId, int fogBrokerId, int parentId) {
//        // Create camera device
//        FogDevice camera = createStandardFogDevice(name, 1000, 1000, 1000, 1000, 1, 0.0, 0.0, 50.0, 40.0);
//        camera.setParentId(parentId);
//        fogDevices.add(camera);
//
//        // Setup camera parameters if needed
//        System.out.println("Camera created: " + camera.getName());
//    }

//    private static FogDevice createStandardFogDevice(String name, int mips, int ram, int bw, int storage, int level,
//                                                     double ratePerMips, double energyModel, double powerModel, double idlePower, double maxPower) {
//        List<Pe> peList = new ArrayList<>();
//        for (int i = 0; i < 1; i++) { // Example: 1 PE
//            peList.add(new Pe(i, new PeProvisionerOverbooking(mips)));
//        }
//
//        return new PowerHost(
//                name,
//                new RamProvisionerSimple(ram),
//                new BwProvisionerOverbooking(bw),
//                storage,
//                peList,
//                new FogLinearPowerModel(idlePower, maxPower),
//                new ArrayList<Storage>(),
//                new
//        );
//    }

//        private static void createActuator(String actuatorName, String appId, int userId, int parentId) {
//        Actuator actuator = new Actuator(actuatorName, userId, appId, "TRAFFIC_LIGHT");
//        actuators.add(actuator);
//        actuator.setGatewayDeviceId(parentId);
}