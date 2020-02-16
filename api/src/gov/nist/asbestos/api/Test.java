package gov.nist.asbestos.api;

import gov.nist.asbestos.sharedObjects.ChannelConfig;

import java.util.List;

public interface Test {
    TestLog run(ChannelConfig channel, TestParms testParms);
    List<TestLog> eval(ChannelConfig channel, int depth);
    boolean isClientTest();
    TestLog getLastLog(TestSession testSession, ChannelConfig channel);
}