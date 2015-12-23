package com.twitter.heron.network;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.twitter.heron.api.generated.TopologyAPI;
import com.twitter.heron.api.utils.Utils;
import com.twitter.heron.common.core.base.Communicator;
import com.twitter.heron.common.core.base.NIOLooper;
import com.twitter.heron.common.core.base.SingletonRegistry;
import com.twitter.heron.common.core.base.SlaveLooper;
import com.twitter.heron.common.core.base.WakeableLooper;
import com.twitter.heron.common.core.network.HeronSocketOptions;
import com.twitter.heron.common.core.network.IncomingPacket;
import com.twitter.heron.common.core.network.OutgoingPacket;
import com.twitter.heron.common.core.network.REQID;
import com.twitter.heron.common.utils.misc.SystemConfig;
import com.twitter.heron.instance.InstanceControlMsg;
import com.twitter.heron.metrics.GatewayMetrics;
import com.twitter.heron.proto.stmgr.StreamManager;
import com.twitter.heron.proto.system.HeronTuples;
import com.twitter.heron.resource.Constants;
import com.twitter.heron.resource.UnitTestHelper;

/**
 * To test whether Instance's handleRead() from the stream manager.
 * 1. The instance will connect to stream manager successfully
 * 2. We will construct a bunch of Mock Message and send them to Instance from Stream manager
 * 3. We will check the inStreamQueue for Instance, whether it contains the Mock Message we send from
 * Stream manager.
 */

public class HandleReadTest {
  private static final String HOST = "127.0.0.1";
  private static int serverPort;

  // Only one outStreamQueue, which is responsible for both control tuples and data tuples
  private Communicator<HeronTuples.HeronTupleSet> outStreamQueue;

  // This blocking queue is used to buffer tuples read from socket and ready to be used by instance
  // For spout, it will buffer Control tuple, while for bolt, it will buffer data tuple.
  private Communicator<HeronTuples.HeronTupleSet> inStreamQueue;

  private Communicator<InstanceControlMsg> inControlQueue;

  private NIOLooper nioLooper;
  private WakeableLooper slaveLooper;

  private StreamManagerClient streamManagerClient;

  private GatewayMetrics gatewayMetrics;

  private ExecutorService threadsPool;

  @BeforeClass
  public static void beforeClass() throws Exception {

  }

  @AfterClass
  public static void afterClass() throws Exception {

  }

  @Before
  public void before() throws Exception {
    UnitTestHelper.addSystemConfigToSingleton();

    nioLooper = new NIOLooper();
    slaveLooper = new SlaveLooper();
    inStreamQueue = new Communicator<HeronTuples.HeronTupleSet>(nioLooper, slaveLooper);
    inStreamQueue.init(Constants.QUEUE_BUFFER_SIZE, Constants.QUEUE_BUFFER_SIZE, 0.5);
    outStreamQueue = new Communicator<HeronTuples.HeronTupleSet>(slaveLooper, nioLooper);
    outStreamQueue.init(Constants.QUEUE_BUFFER_SIZE, Constants.QUEUE_BUFFER_SIZE, 0.5);
    inControlQueue = new Communicator<InstanceControlMsg>(nioLooper, slaveLooper);

    gatewayMetrics = new GatewayMetrics();

    threadsPool = Executors.newSingleThreadExecutor();

    // Get an available port
    serverPort = Utils.getFreePort();
  }

  @After
  public void after() throws Exception {
    UnitTestHelper.clearSingletonRegistry();

    streamManagerClient.stop();
    streamManagerClient = null;

    nioLooper.exitLoop();
    nioLooper = null;
    slaveLooper = null;
    inStreamQueue = null;
    outStreamQueue = null;

    gatewayMetrics = null;

    threadsPool.shutdownNow();
    threadsPool = null;
  }

  @Test
  public void testHandleRead() throws Exception {
    ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
    serverSocketChannel.socket().bind(new InetSocketAddress(HOST, serverPort));

    SocketChannel socketChannel = null;
    try {
      runStreamManagerClient();

      socketChannel = serverSocketChannel.accept();
      configure(socketChannel);
      socketChannel.configureBlocking(false);
      close(serverSocketChannel);

      // Receive request
      IncomingPacket incomingPacket = new IncomingPacket();
      while (incomingPacket.readFromChannel(socketChannel) != 0) {

      }

      // Send back response
      // Though we do not use typeName, we need to unpack it first,
      // since the order is required
      String typeName = incomingPacket.unpackString();
      REQID rid = incomingPacket.unpackREQID();

      OutgoingPacket outgoingPacket
          = new OutgoingPacket(rid, UnitTestHelper.getRegisterInstanceResponse());
      outgoingPacket.writeToChannel(socketChannel);

      for (int i = 0; i < Constants.RETRY_TIMES; i++) {
        InstanceControlMsg instanceControlMsg = inControlQueue.poll();
        if (instanceControlMsg != null) {
          break;
        } else {
          Utils.sleep(Constants.RETRY_INTERVAL_MS);
        }
      }

      outgoingPacket = new OutgoingPacket(REQID.zeroREQID, constructMockMessage());
      outgoingPacket.writeToChannel(socketChannel);

      for (int i = 0; i < Constants.RETRY_TIMES; i++) {
        if (!inStreamQueue.isEmpty()) {
          break;
        }
        Utils.sleep(Constants.RETRY_INTERVAL_MS);
      }
      nioLooper.exitLoop();

      Assert.assertEquals(1, inStreamQueue.size());
      HeronTuples.HeronTupleSet msg = inStreamQueue.poll();

      HeronTuples.HeronTupleSet heronTupleSet = msg;

      Assert.assertTrue(heronTupleSet.hasData());
      Assert.assertFalse(heronTupleSet.hasControl());

      HeronTuples.HeronDataTupleSet heronDataTupleSet = heronTupleSet.getData();

      Assert.assertEquals("test-spout", heronDataTupleSet.getStream().getComponentName());
      Assert.assertEquals("default", heronDataTupleSet.getStream().getId());

      String res = "";
      for (HeronTuples.HeronDataTuple heronDataTuple : heronDataTupleSet.getTuplesList()) {
        res += heronDataTuple.getValues(0).toStringUtf8();
        Assert.assertEquals(1, heronDataTuple.getRootsCount());
      }

      Assert.assertEquals("ABABABABAB", res);
    } catch (ClosedByInterruptException ignored) {
    } catch (ClosedChannelException ignored) {
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      close(socketChannel);
    }
  }

  static void close(Closeable sc2) {
    if (sc2 != null) try {
      sc2.close();
    } catch (IOException ignored) {
    }
  }

  static void configure(SocketChannel sc) throws SocketException {
    sc.socket().setTcpNoDelay(true);
  }

  private Message constructMockMessage() {
    StreamManager.TupleMessage.Builder message = StreamManager.TupleMessage.newBuilder();
    HeronTuples.HeronTupleSet.Builder heronTupleSet = HeronTuples.HeronTupleSet.newBuilder();
    HeronTuples.HeronDataTupleSet.Builder dataTupleSet = HeronTuples.HeronDataTupleSet.newBuilder();
    TopologyAPI.StreamId.Builder streamId = TopologyAPI.StreamId.newBuilder();
    streamId.setComponentName("test-spout");
    streamId.setId("default");
    dataTupleSet.setStream(streamId);

    // We will add 10 tuples to the set
    for (int i = 0; i < 10; i++) {
      HeronTuples.HeronDataTuple.Builder dataTuple = HeronTuples.HeronDataTuple.newBuilder();
      dataTuple.setKey(19901017 + i);

      HeronTuples.RootId.Builder rootId = HeronTuples.RootId.newBuilder();
      rootId.setKey(19901017 + i);
      rootId.setTaskid(0);
      dataTuple.addRoots(rootId);

      String s = "";
      if ((i & 1) == 0) {
        s = "A";
      } else {
        s = "B";
      }
      ByteString byteString = ByteString.copyFrom(s.getBytes());
      dataTuple.addValues(byteString);

      dataTupleSet.addTuples(dataTuple);
    }

    heronTupleSet.setData(dataTupleSet);
    message.setSet(heronTupleSet);

    return message.build();
  }

  void runStreamManagerClient() {
    Runnable r = new Runnable() {
      @Override
      public void run() {
        try {
          SystemConfig systemConfig =
              (SystemConfig) SingletonRegistry.INSTANCE.getSingleton(
                  com.twitter.heron.common.utils.misc.Constants.HERON_SYSTEM_CONFIG);

          HeronSocketOptions socketOptions = new HeronSocketOptions(
              systemConfig.getInstanceNetworkWriteBatchSizeBytes(),
              systemConfig.getInstanceNetworkWriteBatchTimeMs(),
              systemConfig.getInstanceNetworkReadBatchSizeBytes(),
              systemConfig.getInstanceNetworkReadBatchTimeMs(),
              systemConfig.getInstanceNetworkOptionsSocketSendBufferSizeBytes(),
              systemConfig.getInstanceNetworkOptionsSocketReceivedBufferSizeBytes()
          );

          streamManagerClient = new StreamManagerClient(nioLooper, HOST, serverPort,
              "topology-name", "topologyId", UnitTestHelper.getInstance("bolt-id"),
              inStreamQueue, outStreamQueue, inControlQueue, socketOptions, gatewayMetrics);
          streamManagerClient.start();
          nioLooper.loop();
        } catch (Exception ignored) {

        }
      }
    };
    threadsPool.execute(r);
  }

}