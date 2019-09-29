package gov.nist.asbestos.client.Base;

import com.google.gson.Gson;
import gov.nist.asbestos.client.events.EventSummary;
import gov.nist.asbestos.client.events.UiEvent;
import gov.nist.asbestos.client.log.SimStore;
import gov.nist.asbestos.simapi.simCommon.SimId;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import static gov.nist.asbestos.client.Base.Dirs.listOfDirectories;
import static gov.nist.asbestos.client.Base.Dirs.listOfFiles;

public class EC {
    public File externalCache;

    public static final String MarkerType = "Marker";

    public EC(File externalCache) {
        this.externalCache = externalCache;
    }

     public List<String> getTestCollectionNames() {
        return getTestCollections().stream().map(File::getName).collect(Collectors.toList());
    }

     List<File> getTestCollections() {
        List<File> collections = new ArrayList<>();

        URL aUrl = EC.class.getResource("/TestCollections/testCollectionRoot.txt");
        String aFile = aUrl.getFile();
        File internalRoot = new File(aFile).getParentFile();
        List<File> intList = listOfFiles(internalRoot);
        collections.addAll(intList);

        File externalRoot = new File(externalCache, "TestCollections");
        List<File> extList = listOfFiles(externalRoot);
        collections.addAll(extList);

        return collections;
    }

     public List<String> getTestsInCollection(String collectionName) {
        return getTests(collectionName).stream().map(File::getName).collect(Collectors.toList());
    }

    public File getTest(String collectionName, String testName) {
        List<File> tests = getTests(collectionName);

        for (File test : tests) {
            if (test.getName().equalsIgnoreCase(testName)) return test;
        }
        return null;
    }

     public List<File> getTests(String collectionName) {
        File root = getTestCollectionBase(collectionName);
        if (root == null)
            return new ArrayList<>();
        return listOfDirectories(root);
    }

    private static Properties defaultProperties = new Properties();
    static {
        defaultProperties.setProperty("TestType", "server");
    }

    public Properties getTestCollectionProperties(String collectionName) {
        Properties props = new Properties();
        File root = getTestCollectionBase(collectionName);
        if (root == null)
            return props;
        File file = new File(root, "TestCollection.properties");
        try {
            props.load(new FileInputStream(file));
        } catch (IOException e) {
            return defaultProperties;
        }
        return props;
    }

    File getTestCollectionBase(String collectionName) {
        File base = externalTestCollectionBase(collectionName);
        if (base != null)
            return base;
        return internalTestCollectionBase(collectionName);
    }

     File externalTestCollectionBase(String collectionName) {
        File externalRoot = new File(externalCache, "TestCollections");
        if (!externalRoot.exists()) return null;
        if (!externalRoot.isDirectory()) return null;
        File collectionRoot = new File(externalRoot, collectionName);
        if (!collectionRoot.exists()) return null;
        if (!collectionRoot.isDirectory()) return null;
        return collectionRoot;
    }

     File internalTestCollectionBase(String collectionName) {
        URL aUrl = getClass().getResource("/TestCollections/testCollectionRoot.txt");
        String aFile = aUrl.getFile();
        File internalRoot = new File(aFile).getParentFile();

        File collectionRoot = new File(internalRoot, collectionName);
        if (collectionRoot.exists() && collectionRoot.isDirectory())
            return collectionRoot;

        return null;
    }

     public File getTestLog(String channelId, String collectionName, String testName) {
        File testLogs = new File(externalCache, "FhirTestLogs");
        File forChannelId = new File(testLogs, channelId);
        File forCollection = new File(forChannelId, collectionName);
        forCollection.mkdirs();
        return new File(forCollection, testName + ".json");
    }

    public List<File> getTestLogs(String testSession, String collectionName) {
        File testLogs = new File(externalCache, "FhirTestLogs");
        File forTestSession = new File(testLogs, testSession);
        File forCollection = new File(forTestSession, collectionName);

        List<File> testLogList = new ArrayList<>();
        File[] tests = forCollection.listFiles();
        if (tests != null) {
            for (File test : tests) {
                String name = test.toString();
                if (!name.endsWith(".json")) continue;
                if (name.startsWith(".")) continue;
                if (name.startsWith("_")) continue;
                testLogList.add(test);
            }
        }
        return testLogList;
    }

    public File getResourceType(String testSession, String channelId, String resourceType) {
        File fhir = fhirDir(testSession, channelId);
        return new File(fhir, resourceType);
    }

    public void buildJsonListingOfEvent(HttpServletResponse resp, String testSession, String channelId, String resourceType, String eventName) {
        File fhir = fhirDir(testSession, channelId);
        if (resourceType.equals("null")) {
            resourceType = resourceTypeForEvent(fhir, eventName);
            if (resourceType == null) {
                resp.setStatus(resp.SC_NOT_FOUND);
                return;
            }
        }
        File resourceTypeFile = new File(fhir, resourceType);
        File eventDir = new File(resourceTypeFile, eventName);

        UiEvent uiEvent = new UiEvent(eventDir);
        uiEvent.eventName = eventName;
        uiEvent.resourceType = resourceType;

        String json = new Gson().toJson(uiEvent);
        resp.setContentType("application/json");
        try {
            resp.getOutputStream().print(json);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        resp.setStatus(resp.SC_OK);
    }

    public File fhirDir(String testSession, String channelId) {
        File psimdb = new File(externalCache, "psimdb");
        File testSessionFile = new File(psimdb, testSession);
        File channelFile = new File(testSessionFile, channelId);
        return new File(channelFile, "fhir");
    }

    public String resourceTypeForEvent(File fhir, String eventName) {
        File[] resourceTypeFiles = fhir.listFiles();
        if (resourceTypeFiles != null) {
            for (File resourceTypeDir : resourceTypeFiles) {
                File[] eventFiles = resourceTypeDir.listFiles();
                if (eventFiles != null) {
                    for (File eventFile : eventFiles) {
                        if (eventFile.getName().equals(eventName)) {
                            return resourceTypeDir.getName();
                        }
                    }
                }
            }
        }
        return null;
    }

    public void buildJsonListingOfEvents(HttpServletResponse resp, String testSession, String channelId, String resourceType) {
        File fhir = new EC(externalCache).fhirDir(testSession, channelId);
        File resourceTypeFile = new File(fhir, resourceType);

        List<String> events = Dirs.dirListingAsStringList(resourceTypeFile);
        returnJsonList(resp, events);
    }

    public void returnJsonList(HttpServletResponse resp, List<String> theList) {
        String json = new Gson().toJson(theList);
        resp.setContentType("application/json");
        try {
            resp.getOutputStream().print(json);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        resp.setStatus(resp.SC_OK);
    }

    public void buildJsonListingOfEventSummaries(HttpServletResponse resp, String testSession, String channelId) {
        File fhir = new EC(externalCache).fhirDir(testSession, channelId);
        List<String> resourceTypes = Dirs.dirListingAsStringList(fhir);
        List<EventSummary> eventSummaries = new ArrayList<>();
        for (String resourceType : resourceTypes) {
            File resourceDir = new File(fhir, resourceType);
            List<String> eventIds = Dirs.dirListingAsStringList(resourceDir);
            for (String eventId : eventIds) {
                File eventFile = new File(resourceDir, eventId);
                EventSummary summary = new EventSummary(eventFile);
                summary.resourceType = resourceType;
                summary.eventName = eventId;
                eventSummaries.add(summary);
            }
        }
        String json = new Gson().toJson(eventSummaries);
        resp.setContentType("application/json");
        try {
            resp.getOutputStream().print(json);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        resp.setStatus(resp.SC_OK);
    }

    public void buildJsonListingOfResourceTypes(HttpServletResponse resp, String testSession, String channelId) {
        File fhir = new EC(externalCache).fhirDir(testSession, channelId);

        List<String> resourceTypes = Dirs.dirListingAsStringList(fhir);
        new EC(externalCache).returnJsonList(resp, resourceTypes);
    }

    public String getLastMarker(String testSession, String channelId) {
        File markerDir = getResourceType(testSession, channelId, MarkerType);
        List<String> markers = Dirs.dirListingAsStringList(markerDir);
        if (markers.size() == 0) {
            return null;
        } else if (markers.size() == 1) {
            return markers.get(0);
        } else {
            markers.sort(String.CASE_INSENSITIVE_ORDER);
            return markers.get(markers.size() - 1);
        }
    }

    public List<File> getEventsSince(SimId simId, String marker) {
        List<File> eventsList = new ArrayList<>();
        SimStore simStore = new SimStore(externalCache, simId);
        List<File> resourceTypeDirs = simStore.getResourceTypeDirs();
        for (File resourceTypeDir : resourceTypeDirs) {
            List<File> events = Dirs.listOfDirectories(resourceTypeDir);
            for (File event : events) {
                String eventName = event.getName();
                if (marker == null)
                    eventsList.add(event);
                else if (eventName.compareTo(marker) < 0)
                    eventsList.add(event);
            }
        }
        return eventsList;
    }
}