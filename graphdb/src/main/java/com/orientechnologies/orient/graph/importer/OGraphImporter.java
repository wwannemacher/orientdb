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
package com.orientechnologies.orient.graph.importer;

import com.orientechnologies.common.listener.OProgressListener;
import com.orientechnologies.common.util.OPair;
import com.orientechnologies.common.util.OTriple;
import com.orientechnologies.orient.core.config.OStorageEntryConfiguration;
import com.orientechnologies.orient.core.db.ODatabase;
import com.orientechnologies.orient.core.index.OIndex;
import com.orientechnologies.orient.core.index.hashindex.local.OMurmurHash3HashFunction;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OProperty;
import com.orientechnologies.orient.core.metadata.schema.OSchema;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.serialization.serializer.binary.impl.index.OSimpleKeySerializer;
import com.tinkerpop.blueprints.impls.orient.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.Math.abs;

/**
 * API to ingest a graph at the maximum speed possible.
 *
 * @since 2.2.12
 * @author Luca Garulli
 */
public class OGraphImporter {
  private final String                                   userName;
  private final String                                   dbUrl;
  private final String                                   password;
  private int                                            parallel              = Runtime.getRuntime().availableProcessors();
  private Boolean                                        useLightWeightEdges   = null;
  private boolean                                        transactional         = true;

  private Map<String, OPair<String, OType>>              vertexClassesUsed     = new HashMap<String, OPair<String, OType>>();
  private List<OTriple<String, String, String>>          edgeClassesUsed       = new ArrayList<OTriple<String, String, String>>();

  private int                                            batchSize             = 50;
  private int                                            queueSize             = 1000;
  private int                                            maxRetry              = 10;

  private OrientGraphFactory                             factory;
  private OrientGraphNoTx                                baseGraph;
  private Map<String, OImporterWorkerThread>             threads               = new HashMap<String, OImporterWorkerThread>();
  private Map<String, Lock>                              locks                 = new HashMap<String, Lock>();
  private Map<String, String>                            vertexIndexNames      = new HashMap<String, String>();
  private Map<String, ConcurrentHashMap<Object, Thread>> pendingVertexCreation = new HashMap<String, ConcurrentHashMap<Object, Thread>>();

  /**
   * Creates a new batch insert procedure by using admin user. It's intended to be used only for a single batch cycle (begin,
   * create..., end)
   *
   * @param iDbURL
   *          db connection URL (plocal:/your/db/path)
   */
  public OGraphImporter(final String iDbURL) {
    this.dbUrl = iDbURL;
    this.userName = "admin";
    this.password = "admin";
  }

  /**
   * Creates a new batch insert procedure. It's intended to be used only for a single batch cycle (begin, create..., end)
   *
   * @param iDbURL
   *          db connection URL (plocal:/your/db/path)
   * @param iUserName
   *          db user name (use admin for new db)
   * @param iPassword
   *          db password (use admin for new db)
   */
  public OGraphImporter(final String iDbURL, final String iUserName, final String iPassword) {
    this.dbUrl = iDbURL;
    this.userName = iUserName;
    this.password = iPassword;
  }

  /**
   * Creates the database (if it does not exist) and initializes batch operations. Call this once, before starting to create
   * vertices and edges.
   *
   */
  public void begin() {
    factory = new OrientGraphFactory(dbUrl, userName, password, false);

    baseGraph = factory.getNoTx();

    if (this.useLightWeightEdges == null) {
      final List<OStorageEntryConfiguration> custom = (List<OStorageEntryConfiguration>) baseGraph.getRawGraph()
          .get(ODatabase.ATTRIBUTES.CUSTOM);
      for (OStorageEntryConfiguration c : custom) {
        if (c.name.equalsIgnoreCase("useLightweightEdges")) {
          this.useLightWeightEdges = Boolean.parseBoolean(c.value);
          break;
        }
      }
      if (this.useLightWeightEdges == null) {
        this.useLightWeightEdges = true;
      }
    }
    createBaseSchema(baseGraph);

    // CREATE ALL THE NEEDED COMBINATION OF THREADS FOR V-[E]-V
    for (OTriple<String, String, String> edgeClass : edgeClassesUsed) {
      final String edgeClassName = edgeClass.getKey();
      final String sourceClassName = edgeClass.getValue().getKey();
      final String destinationClassName = edgeClass.getValue().getValue();

      for (int sourceClusterIndex = 0; sourceClusterIndex < parallel; ++sourceClusterIndex) {
        for (int destinationClusterIndex = 0; destinationClusterIndex < parallel; ++destinationClusterIndex) {
          final OImporterWorkerThread t = new OImporterWorkerThread(this, sourceClassName, sourceClusterIndex, destinationClassName,
              destinationClusterIndex, edgeClassName);
          t.start();

          threads.put(sourceClassName + "_" + sourceClusterIndex + "-[" + edgeClassName + "]-" + destinationClassName + "_"
              + destinationClusterIndex, t);
        }
      }
    }

    for (Map.Entry<String, OPair<String, OType>> vertexClass : vertexClassesUsed.entrySet()) {
      for (int vertexClusterIndex = 0; vertexClusterIndex < parallel; ++vertexClusterIndex) {
        final OImporterWorkerThread t = new OImporterWorkerThread(this, vertexClass.getKey(), vertexClusterIndex);
        t.start();
        threads.put(vertexClass.getKey() + "_" + vertexClusterIndex, t);
      }
    }

    // CREATE ONE LOCK PER CLUSTER ONLY FOR THE VERTEX CLASSES USED
    for (Map.Entry<String, OPair<String, OType>> entry : vertexClassesUsed.entrySet()) {
      final String className = entry.getKey();
      final int[] clusterIds = baseGraph.getRawGraph().getMetadata().getSchema().getClass(className).getClusterIds();

      for (int clusterId : clusterIds) {
        locks.put(baseGraph.getRawGraph().getClusterNameById(clusterId), new ReentrantLock());
      }
    }

    // CREATE ONE LOCK PER CLUSTER ONLY FOR THE EDGE CLASSES USED
    for (OTriple<String, String, String> edgeClass : edgeClassesUsed) {
      final int[] clusterIds = baseGraph.getRawGraph().getMetadata().getSchema().getClass(edgeClass.getKey()).getClusterIds();
      for (int clusterId : clusterIds) {
        locks.put(baseGraph.getRawGraph().getClusterNameById(clusterId), new ReentrantLock());
      }
    }

    baseGraph.makeActive();
  }

  /**
   * Flushes data to db and closes the db. Call this once, after vertices and edges creation.
   */
  public void end() {
    baseGraph.shutdown();

    factory.close();
  }

  /**
   * Creates a vertex.
   *
   * @param className
   *          Vertex's class name. The class must be declared first by calling {@link #registerVertexClass(String, String, OType)}
   *          method
   * @param id
   *          Vertex's id
   * @param properties
   *          Vertex's properties
   * @throws InterruptedException
   */
  public void createVertex(final String className, final Object id, final Map<String, Object> properties)
      throws InterruptedException {

    if (!vertexClassesUsed.containsKey(className))
      throw new IllegalStateException("Cannot find a vertex of class '" + className
          + "' because it has not been declared at first, by calling registerVertexClass() method");

    // MERGE THE EXISTENT VERTEX ON ITS QUEUE/THREAD
    getThread(baseGraph, className, id).sendOperation(new OCreateVertexOperation(className, id, properties));
  }

  /**
   *
   * @param sourceVertexClassName
   *          Source vertex's class name. The class must be declared first by calling
   *          {@link #registerVertexClass(String, String, OType)} method
   * @param sourceVertexId
   *          Source vertex's id
   * @param destinationVertexClassName
   *          Destination vertex's class name. The class must be declared first by calling
   *          {@link #registerVertexClass(String, String, OType)} method
   * @param destinationVertexId
   *          Destination vertex's id
   * @param properties
   *          Properties to set in the new edge, null to don't set properties
   * @throws InterruptedException
   */
  public void createEdge(final String edgeClassName, final String sourceVertexClassName, final Object sourceVertexId,
      final String destinationVertexClassName, final Object destinationVertexId, final Map<String, Object> properties)
      throws InterruptedException {
    if (!vertexClassesUsed.containsKey(sourceVertexClassName))
      throw new IllegalStateException("Cannot find a vertex of class '" + sourceVertexClassName
          + "' because it has not been declared at first, by calling registerVertexClass() method");

    if (!vertexClassesUsed.containsKey(destinationVertexClassName))
      throw new IllegalStateException("Cannot find a vertex of class '" + destinationVertexClassName
          + "' because it has not been declared at first, by calling registerVertexClass() method");

    getThread(baseGraph, sourceVertexClassName, sourceVertexId, destinationVertexClassName, destinationVertexId, edgeClassName)
        .sendOperation(new OCreateEdgeOperation(edgeClassName, sourceVertexClassName, sourceVertexId, destinationVertexClassName,
            destinationVertexId, properties));
  }

  /**
   * Registers a vertex class to be used in the importing process. Before the importing starts, the class, property and indexes are
   * created if not already existent.
   * 
   * @param vertexClassName
   *          Vertex's lass name
   * @param idPropertyName
   *          Vertex's id property name
   * @param idPropertyType
   *          Vertex's id property type
   */
  public void registerVertexClass(final String vertexClassName, final String idPropertyName, final OType idPropertyType) {
    if (factory != null)
      throw new IllegalStateException("Cannot register vertex classes after the import begun");
    vertexClassesUsed.put(vertexClassName, new OPair<String, OType>(idPropertyName, idPropertyType));
    pendingVertexCreation.put(vertexClassName, new ConcurrentHashMap<Object, Thread>());
  }

  public OPair<String, OType> getRegisteredVertexClass(final String name) {
    return vertexClassesUsed.get(name);
  }

  /**
   * Registers an edge class to be used in the importing process. Before the importing starts, the class is created if not already
   * existent.
   *
   * @param edgeClassName
   *          Vertex's lass name
   */
  public void registerEdgeClass(final String edgeClassName, final String sourceVertexClassName,
      final String destinationVertexClassName) {
    if (factory != null)
      throw new IllegalStateException("Cannot register edge classes after the import begun");
    edgeClassesUsed.add(new OTriple<String, String, String>(edgeClassName, sourceVertexClassName, destinationVertexClassName));
  }

  public boolean isRegisteredEdgeClass(final String name) {
    return vertexClassesUsed.containsKey(name);
  }

  public OrientGraphFactory getFactory() {
    return factory;
  }

  public OIndex<?> getVertexIndex(final OrientBaseGraph graph, final String name) {
    return graph.getRawGraph().getMetadata().getIndexManager().getIndex(vertexIndexNames.get(name));
  }

  /**
   *
   * @return number of parallel threads used for batch import
   */
  public int getParallel() {
    return parallel;
  }

  /**
   * sets the number of parallel threads to be used for batch insert
   *
   * @param parallel
   *          number of threads (default 4)
   */
  public void setParallel(int parallel) {
    this.parallel = parallel;
  }

  public int getQueueSize() {
    return queueSize;
  }

  public void setQueueSize(final int queueSize) {
    this.queueSize = queueSize;
  }

  public int getMaxRetry() {
    return maxRetry;
  }

  public void setMaxRetry(final int maxRetry) {
    this.maxRetry = maxRetry;
  }

  public boolean isTransactional() {
    return transactional;
  }

  public void setTransactional(final boolean transactional) {
    this.transactional = transactional;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(final int batchSize) {
    this.batchSize = batchSize;
  }

  public void lockClusters(final String source, final String destination, final String edge) {
    final List<String> list = new ArrayList<String>(3);
    list.add(source);

    if (destination != null)
      list.add(destination);

    if (edge != null)
      list.add(edge);

    if (list.size() > 1)
      Collections.sort(list);

    // LOCK ORDERED CLUSTERS
    for (String c : list) {
      locks.get(c).lock();
    }
  }

  public void unlockClusters(final String source, final String destination, final String edge) {
    locks.get(source).unlock();

    if (destination != null)
      locks.get(destination).unlock();

    if (edge != null)
      locks.get(edge).unlock();
  }

  public void lockVertexCreationByKey(final String vClassName, final Object key) {
    final Thread currentThread = Thread.currentThread();

    final ConcurrentHashMap<Object, Thread> lockManager = pendingVertexCreation.get(vClassName);
    while (true) {
      final Thread existent = lockManager.putIfAbsent(key, currentThread);
      if (existent == null || existent.equals(currentThread))
        break;

      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        break;
      }
    }
  }

  public void unlockCreationCurrentThread() {
    final Thread currentThread = Thread.currentThread();

    for (ConcurrentHashMap<Object, Thread> lockManager : pendingVertexCreation.values()) {
      for (Iterator<Map.Entry<Object, Thread>> it = lockManager.entrySet().iterator(); it.hasNext();) {
        final Map.Entry<Object, Thread> entry = it.next();
        if (entry.getValue().equals(currentThread))
          it.remove();
      }
    }
  }

  private void createBaseSchema(final OrientGraphNoTx db) {
    final OSchema schema = db.getRawGraph().getMetadata().getSchema();

    // ASSURE BASE V & E CLASSES ARE CREATED
    OClass v = schema.getClass(OrientVertexType.CLASS_NAME);
    if (v == null)
      v = schema.createClass(OrientVertexType.CLASS_NAME);

    OClass e = schema.getClass(OrientEdgeType.CLASS_NAME);
    if (e == null)
      e = schema.createClass(OrientEdgeType.CLASS_NAME);

    // CREATE ALL THE DECLARED VERTEX CLASSES
    for (Map.Entry<String, OPair<String, OType>> entry : vertexClassesUsed.entrySet()) {
      final String className = entry.getKey();
      final String propertyName = entry.getValue().getKey();
      final OType propertyValue = entry.getValue().getValue();

      OClass cls = schema.getClass(className);
      if (cls == null)
        cls = schema.createClass(className, v);

      OProperty prop = cls.getProperty(propertyName);
      if (prop == null) {
        prop = cls.createProperty(propertyName, propertyValue);
      }

      OIndex<?> index = null;
      if (!prop.getAllIndexes().isEmpty()) {
        // LOOK FOR THE INDEX
        for (OIndex<?> idx : prop.getAllIndexes()) {
          if (idx.getAlgorithm().equalsIgnoreCase("AUTOSHARDING")) {
            // FOUND
            index = idx;
            break;
          }
        }
      }

      if (index == null)
        // CREATE THE INDEX
        index = cls.createIndex(className + "." + propertyName, OClass.INDEX_TYPE.UNIQUE.toString(), (OProgressListener) null,
            (ODocument) null, "AUTOSHARDING", new String[] { propertyName });

      // REGISTER THE INDEX
      vertexIndexNames.put(className, index.getName());

      // ASSURE THERE ARE ENOUGH NUMBER OF CLUSTERS, OTHERWISE CREATE THE MISSING ONES
      int[] existingClusters = cls.getClusterIds();
      for (int c = existingClusters.length; c <= parallel; c++) {
        cls.addCluster(cls.getName() + "_" + c);
      }
    }

    // CREATE ALL THE DECLARED EDGE CLASSES
    for (OTriple<String, String, String> edgeClass : edgeClassesUsed) {
      OClass cls = schema.getClass(edgeClass.getKey());

      if (cls == null)
        cls = schema.createClass(edgeClass.getKey(), e);

      // ASSURE THERE ARE ENOUGH NUMBER OF CLUSTERS, OTHERWISE CREATE THE MISSING ONES
      int[] existingClusters = cls.getClusterIds();
      for (int c = existingClusters.length; c <= parallel; c++) {
        cls.addCluster(cls.getName() + "_" + c);
      }
    }
  }

  private OImporterWorkerThread getThread(final OrientBaseGraph graph, final String sourceClassName, final Object sourceKey,
      final String destinationClassName, final Object destinationKey, final String edgeClassName) {
    final OMurmurHash3HashFunction sourceHash = new OMurmurHash3HashFunction();
    final OType sourceKeyType = getVertexIndex(graph, sourceClassName).getKeyTypes()[0];
    sourceHash.setValueSerializer(new OSimpleKeySerializer(sourceKeyType));

    long sourceValue = sourceHash.hashCode(sourceKey);
    sourceValue = sourceValue == Long.MIN_VALUE ? 0 : abs(sourceValue);
    sourceValue = sourceValue % parallel;

    final OType destinationKeyType = getVertexIndex(graph, destinationClassName).getKeyTypes()[0];

    final OMurmurHash3HashFunction destinationHash;
    if (destinationKeyType.equals(sourceKeyType))
      destinationHash = sourceHash;
    else
      destinationHash = new OMurmurHash3HashFunction();

    long destinationValue = destinationHash.hashCode(destinationKey);
    destinationValue = destinationValue == Long.MIN_VALUE ? 0 : abs(destinationValue);
    destinationValue = destinationValue % parallel;

    return threads
        .get(sourceClassName + "_" + sourceValue + "-[" + edgeClassName + "]-" + destinationClassName + "_" + destinationValue);
  }

  private OImporterWorkerThread getThread(final OrientBaseGraph graph, final String className, final Object key) {
    final OMurmurHash3HashFunction hash = new OMurmurHash3HashFunction();
    final OType keyType = getVertexIndex(graph, className).getKeyTypes()[0];
    hash.setValueSerializer(new OSimpleKeySerializer(keyType));

    long sourceValue = hash.hashCode(key);
    sourceValue = sourceValue == Long.MIN_VALUE ? 0 : abs(sourceValue);
    sourceValue = sourceValue % parallel;

    return threads.get(className + "_" + sourceValue + "->" + className + "_" + sourceValue);
  }
}
