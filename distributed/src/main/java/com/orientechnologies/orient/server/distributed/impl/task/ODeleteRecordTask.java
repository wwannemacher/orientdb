/*
 *
 *  *  Copyright 2014 Orient Technologies LTD (info(at)orientechnologies.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://www.orientechnologies.com
 *
 */
package com.orientechnologies.orient.server.distributed.impl.task;

import com.orientechnologies.orient.core.command.OCommandDistributedReplicateRequest;
import com.orientechnologies.orient.core.db.ODatabaseDocumentInternal;
import com.orientechnologies.orient.core.exception.ORecordNotFoundException;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.graph.sql.OGraphCommandExecutorSQLFactory;
import com.orientechnologies.orient.server.OServer;
import com.orientechnologies.orient.server.distributed.ODistributedRequest;
import com.orientechnologies.orient.server.distributed.ODistributedRequestId;
import com.orientechnologies.orient.server.distributed.ODistributedServerLog;
import com.orientechnologies.orient.server.distributed.ODistributedServerLog.DIRECTION;
import com.orientechnologies.orient.server.distributed.ODistributedServerManager;
import com.orientechnologies.orient.server.distributed.task.OAbstractRecordReplicatedTask;
import com.orientechnologies.orient.server.distributed.task.ORemoteTask;
import com.tinkerpop.blueprints.impls.orient.OrientBaseGraph;

import java.util.List;

/**
 * Distributed delete record task used for synchronization.
 *
 * @author Luca Garulli (l.garulli--at--orientechnologies.com)
 */
public class ODeleteRecordTask extends OAbstractRecordReplicatedTask {
  public static final int FACTORYID = 4;

  public ODeleteRecordTask() {
  }

  public ODeleteRecordTask(final ORecord record) {
    init(record);
  }

  public ODeleteRecordTask init(final ORecordId rid, final int version) {
    super.init(rid, version);
    return this;
  }

  @Override
  public ORecord getRecord() {
    return null;
  }

  @Override
  public Object executeRecordTask(ODistributedRequestId requestId, final OServer iServer, ODistributedServerManager iManager,
      final ODatabaseDocumentInternal database) throws Exception {
    ODistributedServerLog
        .debug(this, iManager.getLocalNodeName(), null, DIRECTION.IN, "Deleting record %s/%s v.%d (reqId=%s)", database.getName(),
            rid.toString(), version, requestId);

    prepareUndoOperation();
    if (previousRecord == null)
      // ALREADY DELETED
      return true;

    final ORecord loadedRecord = previousRecord.copy();

    try {
      if (loadedRecord instanceof ODocument) {
        if (((ODocument) loadedRecord).getSchemaClass().isEdgeType()) {
          // DELETE EDGE
          OGraphCommandExecutorSQLFactory.runInConfiguredTxMode(new OGraphCommandExecutorSQLFactory.GraphCallBack<Object>() {
            @Override
            public Object call(OrientBaseGraph graph) {
              graph.getEdge(loadedRecord).remove();
              return null;
            }
          });
          return true;
        } else if (((ODocument) loadedRecord).getSchemaClass().isVertexType()) {
          // DELETE VERTEX
          OGraphCommandExecutorSQLFactory.runInConfiguredTxMode(new OGraphCommandExecutorSQLFactory.GraphCallBack<Object>() {
            @Override
            public Object call(OrientBaseGraph graph) {
              graph.getVertex(loadedRecord).remove();
              return null;
            }
          });
          return true;
        }
      }

      loadedRecord.delete();

    } catch (ORecordNotFoundException e) {
      // NO ERROR, IT WAS ALREADY DELETED
    }

    return true;
  }

  @Override
  public OCommandDistributedReplicateRequest.QUORUM_TYPE getQuorumType() {
    return OCommandDistributedReplicateRequest.QUORUM_TYPE.WRITE;
  }

  @Override
  public ORemoteTask getFixTask(final ODistributedRequest iRequest, final ORemoteTask iOriginalTask, final Object iBadResponse,
      final Object iGoodResponse, String executorNodeName, ODistributedServerManager dManager) {
    return ((OFixCreateRecordTask) dManager.getTaskFactoryManager().getFactoryByServerName(executorNodeName)
        .createTask(OFixCreateRecordTask.FACTORYID)).init(rid, version);
  }

  @Override
  public ORemoteTask getUndoTask(ODistributedServerManager dManager, final ODistributedRequestId reqId, List<String> servers) {
    if (previousRecord == null)
      return null;

    final OResurrectRecordTask task = ((OResurrectRecordTask) dManager.getTaskFactoryManager().getFactoryByServerNames(servers)
        .createTask(OResurrectRecordTask.FACTORYID)).init(previousRecord);
    task.setLockRecords(false);
    return task;
  }

  @Override
  public void checkRecordExists() {
    // AVOID TO RETURN RECORD NOT FOUND IF ALREADY DELETED
  }

  @Override
  public String getName() {
    return "record_delete";
  }

  @Override
  public String toString() {
    return getName() + "(" + rid + ")";
  }

  @Override
  public int getFactoryId() {
    return FACTORYID;
  }

  @Override
  public boolean isIdempotent() {
    return false;
  }

}
