/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.service;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.*;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.concurrent.Stage;
import org.apache.cassandra.concurrent.StageManager;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.KSMetaData;
import org.apache.cassandra.config.UTMetaData;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.marshal.UserType;
import org.apache.cassandra.exceptions.AlreadyExistsException;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.gms.*;
import org.apache.cassandra.io.IVersionedSerializer;
import org.apache.cassandra.net.MessageOut;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.WrappedRunnable;

public class MigrationManager implements IEndpointStateChangeSubscriber
{
    private static final Logger logger = LoggerFactory.getLogger(MigrationManager.class);

    public static final MigrationManager instance = new MigrationManager();

    private static final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();

    public static final int MIGRATION_DELAY_IN_MS = 60000;

    private final List<IMigrationListener> listeners = new CopyOnWriteArrayList<IMigrationListener>();

    private MigrationManager() {}

    public void register(IMigrationListener listener)
    {
        listeners.add(listener);
    }

    public void unregister(IMigrationListener listener)
    {
        listeners.remove(listener);
    }

    public void onJoin(InetAddress endpoint, EndpointState epState)
    {}
    
    public void beforeChange(InetAddress endpoint, EndpointState currentState, ApplicationState newStateKey, VersionedValue newValue) 
    {}

    public void onChange(InetAddress endpoint, ApplicationState state, VersionedValue value)
    {
        if (state != ApplicationState.SCHEMA || endpoint.equals(FBUtilities.getBroadcastAddress()))
            return;

        maybeScheduleSchemaPull(UUID.fromString(value.value), endpoint);
    }

    public void onAlive(InetAddress endpoint, EndpointState state)
    {
        VersionedValue value = state.getApplicationState(ApplicationState.SCHEMA);

        if (value != null)
            maybeScheduleSchemaPull(UUID.fromString(value.value), endpoint);
    }

    public void onDead(InetAddress endpoint, EndpointState state)
    {}

    public void onRestart(InetAddress endpoint, EndpointState state)
    {}

    public void onRemove(InetAddress endpoint)
    {}

    /**
     * If versions differ this node sends request with local migration list to the endpoint
     * and expecting to receive a list of migrations to apply locally.
     */
    private static void maybeScheduleSchemaPull(final UUID theirVersion, final InetAddress endpoint)
    {
        if ((Schema.instance.getVersion() != null && Schema.instance.getVersion().equals(theirVersion)) || !shouldPullSchemaFrom(endpoint))
            return;

        if (Schema.emptyVersion.equals(Schema.instance.getVersion()) || runtimeMXBean.getUptime() < MIGRATION_DELAY_IN_MS)
        {
            // If we think we may be bootstrapping or have recently started, submit MigrationTask immediately
            submitMigrationTask(endpoint);
        }
        else
        {
            // Include a delay to make sure we have a chance to apply any changes being
            // pushed out simultaneously. See CASSANDRA-5025
            Runnable runnable = new Runnable()
            {
                public void run()
                {
                    // grab the latest version of the schema since it may have changed again since the initial scheduling
                    EndpointState epState = Gossiper.instance.getEndpointStateForEndpoint(endpoint);
                    if (epState == null)
                        return;
                    VersionedValue value = epState.getApplicationState(ApplicationState.SCHEMA);
                    UUID currentVersion = UUID.fromString(value.value);
                    if (Schema.instance.getVersion().equals(currentVersion))
                        return;

                    submitMigrationTask(endpoint);
                }
            };
            StorageService.optionalTasks.schedule(runnable, MIGRATION_DELAY_IN_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static Future<?> submitMigrationTask(InetAddress endpoint)
    {
        /*
         * Do not de-ref the future because that causes distributed deadlock (CASSANDRA-3832) because we are
         * running in the gossip stage.
         */
        return StageManager.getStage(Stage.MIGRATION).submit(new MigrationTask(endpoint));
    }

    private static boolean shouldPullSchemaFrom(InetAddress endpoint)
    {
        /*
         * Don't request schema from nodes with a higher major (may have incompatible schema)
         * Don't request schema from fat clients
         */
        return MessagingService.instance().getVersion(endpoint) <= MessagingService.current_version
            && !Gossiper.instance.isFatClient(endpoint);
    }

    public static boolean isReadyForBootstrap()
    {
        return ((ThreadPoolExecutor) StageManager.getStage(Stage.MIGRATION)).getActiveCount() == 0;
    }

    public void notifyCreateKeyspace(KSMetaData ksm)
    {
        for (IMigrationListener listener : listeners)
            listener.onCreateKeyspace(ksm.name);
    }

    public void notifyCreateColumnFamily(CFMetaData cfm)
    {
        for (IMigrationListener listener : listeners)
            listener.onCreateColumnFamily(cfm.ksName, cfm.cfName);
    }

    public void notifyUpdateKeyspace(KSMetaData ksm)
    {
        for (IMigrationListener listener : listeners)
            listener.onUpdateKeyspace(ksm.name);
    }

    public void notifyUpdateColumnFamily(CFMetaData cfm)
    {
        for (IMigrationListener listener : listeners)
            listener.onUpdateColumnFamily(cfm.ksName, cfm.cfName);
    }

    public void notifyDropKeyspace(KSMetaData ksm)
    {
        for (IMigrationListener listener : listeners)
            listener.onDropKeyspace(ksm.name);
    }

    public void notifyDropColumnFamily(CFMetaData cfm)
    {
        for (IMigrationListener listener : listeners)
            listener.onDropColumnFamily(cfm.ksName, cfm.cfName);
    }

    //通告有新的Keyspace了，会更新本地和其它节点
    public static void announceNewKeyspace(KSMetaData ksm) throws ConfigurationException
    {
        announceNewKeyspace(ksm, FBUtilities.timestampMicros()); //获得当前时间，按微妙算
    }

    public static void announceNewKeyspace(KSMetaData ksm, long timestamp) throws ConfigurationException
    {
        ksm.validate(); //检查AbstractReplicationStrategy和CFMetaData

        if (Schema.instance.getKSMetaData(ksm.name) != null)
            throw new AlreadyExistsException(ksm.name);

        logger.info(String.format("Create new Keyspace: %s", ksm));
        announce(ksm.toSchema(timestamp));
    }

    public static void announceNewColumnFamily(CFMetaData cfm) throws ConfigurationException
    {
        cfm.validate();

        KSMetaData ksm = Schema.instance.getKSMetaData(cfm.ksName);
        if (ksm == null)
            throw new ConfigurationException(String.format("Cannot add column family '%s' to non existing keyspace '%s'.", cfm.cfName, cfm.ksName));
        else if (ksm.cfMetaData().containsKey(cfm.cfName))
            throw new AlreadyExistsException(cfm.ksName, cfm.cfName);

        logger.info(String.format("Create new ColumnFamily: %s", cfm));
        announce(cfm.toSchema(FBUtilities.timestampMicros()));
    }

    public static void announceNewType(UserType newType)
    {
        announce(UTMetaData.toSchema(newType, FBUtilities.timestampMicros()));
    }

    public static void announceKeyspaceUpdate(KSMetaData ksm) throws ConfigurationException
    {
        ksm.validate();

        KSMetaData oldKsm = Schema.instance.getKSMetaData(ksm.name);
        if (oldKsm == null)
            throw new ConfigurationException(String.format("Cannot update non existing keyspace '%s'.", ksm.name));

        logger.info(String.format("Update Keyspace '%s' From %s To %s", ksm.name, oldKsm, ksm));
        announce(oldKsm.toSchemaUpdate(ksm, FBUtilities.timestampMicros()));
    }

    public static void announceColumnFamilyUpdate(CFMetaData cfm, boolean fromThrift) throws ConfigurationException
    {
        cfm.validate();

        CFMetaData oldCfm = Schema.instance.getCFMetaData(cfm.ksName, cfm.cfName);
        if (oldCfm == null)
            throw new ConfigurationException(String.format("Cannot update non existing column family '%s' in keyspace '%s'.", cfm.cfName, cfm.ksName));

        oldCfm.validateCompatility(cfm);

        logger.info(String.format("Update ColumnFamily '%s/%s' From %s To %s", cfm.ksName, cfm.cfName, oldCfm, cfm));
        announce(oldCfm.toSchemaUpdate(cfm, FBUtilities.timestampMicros(), fromThrift));
    }

    public static void announceTypeUpdate(UserType updatedType)
    {
        // We don't make a difference with a new type. DefsTable.mergeType will make sure we keep the updated version.
        announceNewType(updatedType);
    }

    public static void announceKeyspaceDrop(String ksName) throws ConfigurationException
    {
        KSMetaData oldKsm = Schema.instance.getKSMetaData(ksName);
        if (oldKsm == null)
            throw new ConfigurationException(String.format("Cannot drop non existing keyspace '%s'.", ksName));

        logger.info(String.format("Drop Keyspace '%s'", oldKsm.name));
        announce(oldKsm.dropFromSchema(FBUtilities.timestampMicros()));
    }

    public static void announceColumnFamilyDrop(String ksName, String cfName) throws ConfigurationException
    {
        CFMetaData oldCfm = Schema.instance.getCFMetaData(ksName, cfName);
        if (oldCfm == null)
            throw new ConfigurationException(String.format("Cannot drop non existing column family '%s' in keyspace '%s'.", cfName, ksName));

        logger.info(String.format("Drop ColumnFamily '%s/%s'", oldCfm.ksName, oldCfm.cfName));
        announce(oldCfm.dropFromSchema(FBUtilities.timestampMicros()));
    }

    public static void announceTypeDrop(UserType droppedType)
    {
        announce(UTMetaData.dropFromSchema(droppedType, FBUtilities.timestampMicros()));
    }

    /**
     * actively announce a new version to active hosts via rpc
     * @param schema The schema mutation to be applied
     */
    private static void announce(Mutation schema)
    {
        FBUtilities.waitOnFuture(announce(Collections.singletonList(schema)));
    }

    private static void pushSchemaMutation(InetAddress endpoint, Collection<Mutation> schema)
    {
        //Verb.DEFINITIONS_UPDATE在org.apache.cassandra.db.DefinitionsUpdateVerbHandler.DefinitionsUpdateVerbHandler()处理
        MessageOut<Collection<Mutation>> msg = new MessageOut<>(MessagingService.Verb.DEFINITIONS_UPDATE,
                                                                schema,
                                                                MigrationsSerializer.instance);
        MessagingService.instance().sendOneWay(msg, endpoint);
    }

    // Returns a future on the local application of the schema
    private static Future<?> announce(final Collection<Mutation> schema)
    {
        Future<?> f = StageManager.getStage(Stage.MIGRATION).submit(new WrappedRunnable()
        {
            protected void runMayThrow() throws IOException, ConfigurationException
            {
                DefsTables.mergeSchema(schema);
            }
        });

        //并不需要等待其他节点更新完成
        for (InetAddress endpoint : Gossiper.instance.getLiveMembers())
        {
            if (endpoint.equals(FBUtilities.getBroadcastAddress()))
                continue; // we've dealt with localhost already

            // don't send schema to the nodes with the versions older than current major
            if (MessagingService.instance().getVersion(endpoint) < MessagingService.current_version)
                continue;

            pushSchemaMutation(endpoint, schema);
        }
        //返回的是Future<?> f，所以FBUtilities.waitOnFuture只等待本地完成就返回
        return f;
    }

    /**
     * Announce my version passively over gossip.
     * Used to notify nodes as they arrive in the cluster.
     *
     * @param version The schema version to announce
     */
    public static void passiveAnnounce(UUID version)
    {
        Gossiper.instance.addLocalApplicationState(ApplicationState.SCHEMA, StorageService.instance.valueFactory.schema(version));
        logger.debug("Gossiping my schema version {}", version);
    }

    /**
     * Clear all locally stored schema information and reset schema to initial state.
     * Called by user (via JMX) who wants to get rid of schema disagreement.
     *
     * @throws IOException if schema tables truncation fails
     */
    public static void resetLocalSchema() throws IOException
    {
        logger.info("Starting local schema reset...");

        logger.debug("Truncating schema tables...");

        // truncate schema tables
        for (String cf : SystemKeyspace.allSchemaCfs)
            SystemKeyspace.schemaCFS(cf).truncateBlocking();

        logger.debug("Clearing local schema keyspace definitions...");

        Schema.instance.clear();

        Set<InetAddress> liveEndpoints = Gossiper.instance.getLiveMembers();
        liveEndpoints.remove(FBUtilities.getBroadcastAddress());

        // force migration if there are nodes around
        for (InetAddress node : liveEndpoints)
        {
            if (shouldPullSchemaFrom(node))
            {
                logger.debug("Requesting schema from {}", node);
                FBUtilities.waitOnFuture(submitMigrationTask(node));
                break;
            }
        }

        logger.info("Local schema reset is complete.");
    }

    public static class MigrationsSerializer implements IVersionedSerializer<Collection<Mutation>>
    {
        public static MigrationsSerializer instance = new MigrationsSerializer();

        public void serialize(Collection<Mutation> schema, DataOutput out, int version) throws IOException
        {
            out.writeInt(schema.size());
            for (Mutation mutation : schema)
                Mutation.serializer.serialize(mutation, out, version);
        }

        public Collection<Mutation> deserialize(DataInput in, int version) throws IOException
        {
            int count = in.readInt();
            Collection<Mutation> schema = new ArrayList<Mutation>(count);

            for (int i = 0; i < count; i++)
                schema.add(Mutation.serializer.deserialize(in, version));

            return schema;
        }

        public long serializedSize(Collection<Mutation> schema, int version)
        {
            int size = TypeSizes.NATIVE.sizeof(schema.size());
            for (Mutation mutation : schema)
                size += Mutation.serializer.serializedSize(mutation, version);
            return size;
        }
    }
}
