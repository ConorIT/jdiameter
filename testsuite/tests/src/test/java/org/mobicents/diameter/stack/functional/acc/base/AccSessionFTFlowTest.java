/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and/or its affiliates, and individual
 * contributors as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a full listing
 * of individual contributors.
 * 
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU General Public License, v. 2.0.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of 
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU 
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License,
 * v. 2.0 along with this distribution; if not, write to the Free 
 * Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301, USA.
 */
package org.mobicents.diameter.stack.functional.acc.base;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.jdiameter.api.Mode;
import org.jdiameter.api.Peer;
import org.jdiameter.api.PeerTable;
import org.jdiameter.api.Stack;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Accounting Fault Tolerance Test
 * 
 * 2 Servers (A, B), 1 Client (C)
 * 
 * Flow:
 * 1. Client C sends INITIAL ACR to any of the servers, let's consider A;
 * 2. Server A receives INITIAL, creates new session, processes it under session,
 *    answers it, and gets killed;
 * 3. Server B fetches session data from cluster datasource;
 * 4. Client C sends INTERIM ACR to B (only peer which is connected);
 * 5. Server B receives INTERIM, processes it under session, answers it;
 * 6. Client C sends TERMINATE ACR to B (only peer which is connected);
 * 7. Server B receives TERMINATE, processes it under session, answers it;
 * 
 * @author <a href="mailto:brainslog@gmail.com"> Alexandre Mendonca </a>
 * @author <a href="mailto:baranowb@gmail.com"> Bartosz Baranowski </a>
 */
@RunWith(Parameterized.class)
public class AccSessionFTFlowTest {

  private static Logger logger = Logger.getLogger(AccSessionFTFlowTest.class);

  private Client clientNode;
  private Server serverNode1;
  private Server serverNode2;
  private URI clientConfigURI;
  private URI serverNode1ConfigURI;
  private URI serverNode2ConfigURI;

  /**
   * @param clientNode
   * @param node1
   * @param node2
   * @param serverCount
   */
  public AccSessionFTFlowTest(String clientConfigUrl, String serverNode1ConfigURL, String serverNode2ConfigURL) throws Exception {
    super();
    this.clientConfigURI = new URI(clientConfigUrl);
    this.serverNode1ConfigURI = new URI(serverNode1ConfigURL);
    if (!serverNode2ConfigURL.equals("")) {
      this.serverNode2ConfigURI = new URI(serverNode2ConfigURL);
    }
  }

  @Before
  public void setUp() throws Exception {
    try {
      this.clientNode = new Client();
      this.serverNode1 = new Server();

      // set stateless == false on both, we don't know which one will be hit by initial request.
      this.serverNode1.init(new FileInputStream(new File(this.serverNode1ConfigURI)), "SERVER1");
      this.serverNode1.setStateless(false);
      this.serverNode1.start();
      if (this.serverNode2ConfigURI != null) {
        this.serverNode2 = new Server();
        this.serverNode2.init(new FileInputStream(new File(this.serverNode2ConfigURI)), "SERVER2");
        this.serverNode2.setStateless(false);
        this.serverNode2.start();
      }

      this.clientNode.init(new FileInputStream(new File(this.clientConfigURI)), "CLIENT");
      this.clientNode.start(Mode.ALL_PEERS, 10, TimeUnit.SECONDS);
      Stack stack = this.clientNode.getStack();
      List<Peer> peers = stack.unwrap(PeerTable.class).getPeerTable();
      if (this.serverNode2 == null && peers.size() == 1) {
        // ok
      }
      else if (this.serverNode2 != null && peers.size() == 2) {
        // ok
      }
      else {
        throw new Exception("Wrong number of connected peers: " + peers);
      }
      // give a wait time for cluster, it should be up and running without that, but... :)
      // ammendonca: commented, was throwing java.lang.IllegalMonitorStateException
      // Thread.currentThread().wait(15000);
    }
    catch (Throwable e) {
      e.printStackTrace();
      fail("Setup failed: " + e.getMessage());
    }
  }

  @After
  public void tearDown() {
    if (this.serverNode2 != null) {
      try {
        this.serverNode2.stop();
      }
      catch (Exception e) {
        logger.warn("Failed to stop SERVER (2) stack.", e);
      }
      this.serverNode2 = null;
    }

    if (this.serverNode1 != null) {
      try {
        this.serverNode1.stop();
      }
      catch (Exception e) {
        logger.warn("Failed to stop SERVER (1) stack.", e);
      }
      this.serverNode1 = null;
    }

    if (this.clientNode != null) {
      try {
        this.clientNode.stop();
      }
      catch (Exception e) {
        logger.warn("Failed to stop CLIENT stack.", e);
      }
      this.clientNode = null;
    }
  }

  @Test
  public void testBasicFlow() throws Exception {
    Server backupServer = null; // server which we will use.
    Server serverToKill = null;
    try {

      clientNode.sendInitial();
      waitForMessage();

      // now lets check which server node got the msg.
      if (serverNode1.isReceiveINITIAL()) {
        backupServer = serverNode2;
        serverToKill = serverNode1;
      }
      else {
        backupServer = serverNode1;
        serverToKill = serverNode2;
      }
      serverToKill.sendInitial();
      waitForMessage();
      // kill
      serverToKill.stop(15, TimeUnit.SECONDS);

      // now we have to update second server, so it gets session;
      backupServer.fetchSession(clientNode.getSessionId());

      // and continue normally for the client
      clientNode.sendInterim();
      waitForMessage();

      backupServer.sendInterim();
      waitForMessage();

      clientNode.sendTermination();
      waitForMessage();

      backupServer.sendTermination();
      waitForMessage();
    }
    catch (Exception e) {
      e.printStackTrace();
      fail(e.toString());
    }

    if (!clientNode.isReceiveINITIAL()) {
      StringBuilder sb = new StringBuilder("Did not receive INITIAL! ");
      sb.append("Client ER:\n").append(clientNode.createErrorReport(this.clientNode.getErrors()));

      fail(sb.toString());
    }
    if (!clientNode.isReceiveINTERIM()) {
      StringBuilder sb = new StringBuilder("Did not receive INTERIM! ");
      sb.append("Client ER:\n").append(clientNode.createErrorReport(this.clientNode.getErrors()));

      fail(sb.toString());
    }
    if (!clientNode.isReceiveTERMINATE()) {
      StringBuilder sb = new StringBuilder("Did not receive TERMINATE! ");
      sb.append("Client ER:\n").append(clientNode.createErrorReport(this.clientNode.getErrors()));

      fail(sb.toString());
    }
    if (!clientNode.isPassed()) {
      StringBuilder sb = new StringBuilder();
      sb.append("Client ER:\n").append(clientNode.createErrorReport(this.clientNode.getErrors()));

      fail(sb.toString());
    }

    if (backupServer != null) {
      if (backupServer.isReceiveINITIAL()) {
        StringBuilder sb = new StringBuilder("Received INITIAL! ");
        sb.append("Server ER:\n").append(backupServer.createErrorReport(backupServer.getErrors()));

        fail(sb.toString());
      }

      if (!backupServer.isReceiveINTERIM()) {
        StringBuilder sb = new StringBuilder("Did not receive INTERIM! ");
        sb.append("Server ER:\n").append(backupServer.createErrorReport(backupServer.getErrors()));

        fail(sb.toString());
      }

      if (!backupServer.isReceiveTERMINATE()) {
        StringBuilder sb = new StringBuilder("Did not receive TERMINATE! ");
        sb.append("Server ER:\n").append(backupServer.createErrorReport(backupServer.getErrors()));

        fail(sb.toString());
      }

      if (!backupServer.isPassed()) {
        StringBuilder sb = new StringBuilder();
        sb.append("Server ER:\n").append(backupServer.createErrorReport(backupServer.getErrors()));

        fail(sb.toString());
      }
    }
    else {
      fail("Backup Server is not present.");
    }

    if (serverToKill != null) {
      if (!serverToKill.isReceiveINITIAL()) {
        StringBuilder sb = new StringBuilder("Did not receive INITIAL! ");
        sb.append("Server ER:\n").append(serverToKill.createErrorReport(serverToKill.getErrors()));

        fail(sb.toString());
      }

      if (serverToKill.isReceiveINTERIM()) {
        StringBuilder sb = new StringBuilder("Received INTERIM! ");
        sb.append("Server ER:\n").append(serverToKill.createErrorReport(serverToKill.getErrors()));

        fail(sb.toString());
      }

      if (serverToKill.isReceiveTERMINATE()) {
        StringBuilder sb = new StringBuilder("Received TERMINATE! ");
        sb.append("Server ER:\n").append(serverToKill.createErrorReport(serverToKill.getErrors()));

        fail(sb.toString());
      }

      if (!serverToKill.isPassed()) {
        StringBuilder sb = new StringBuilder();
        sb.append("Server ER:\n").append(serverToKill.createErrorReport(backupServer.getErrors()));

        fail(sb.toString());
      }
    }
    else {
      fail("Initial Server is not present.");
    }
  }

  @Parameters
  public static Collection<Object[]> data() {
    String replicatedClient = "replicated-config-client.xml";
    String replicatedServer1 = "replicated-config-server-node1.xml";
    String replicatedServer2 = "replicated-config-server-node2.xml";

    Class<AccSessionFTFlowTest> t = AccSessionFTFlowTest.class;

    replicatedClient = t.getResource(replicatedClient).toString();
    replicatedServer1 = t.getResource(replicatedServer1).toString();
    replicatedServer2 = t.getResource(replicatedServer2).toString();

    return Arrays.asList(new Object[][] { { replicatedClient, replicatedServer1, replicatedServer2 } });
  }

  private void waitForMessage() {
    try {
      Thread.sleep(2000);
    }
    catch (InterruptedException e) {
      logger.error("", e);
    }
  }

}
