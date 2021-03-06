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
package org.apache.cassandra.db;

import java.io.*;
import java.security.MessageDigest;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.db.composites.CellName;
import org.apache.cassandra.db.composites.CellNameType;
import org.apache.cassandra.db.composites.Composite;
import org.apache.cassandra.io.ISSTableSerializer;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.serializers.MarshalException;

//有6个子类:
//org.apache.cassandra.db.Column
//    org.apache.cassandra.db.CounterColumn
//    org.apache.cassandra.db.CounterUpdateColumn
//    org.apache.cassandra.db.DeletedColumn
//    org.apache.cassandra.db.ExpiringColumn
//org.apache.cassandra.db.RangeTombstone

//RangeTombstone用org.apache.cassandra.db.RangeTombstone.Serializer来做序列化
//其他5个子类都用org.apache.cassandra.db.ColumnSerializer做序列化
public interface OnDiskAtom
{
    public Composite name();

    /**
     * For a standard column, this is the same as timestamp().
     * For a super column, this is the min/max column timestamp of the sub columns.
     */
    public long minTimestamp();
    public long maxTimestamp();
    public int getLocalDeletionTime(); // for tombstone GC, so int is sufficient granularity

    public void validateFields(CFMetaData metadata) throws MarshalException;
    public void updateDigest(MessageDigest digest);

    public static class Serializer implements ISSTableSerializer<OnDiskAtom>
    {
        private final CellNameType type;

        public Serializer(CellNameType type)
        {
            this.type = type;
        }

        //OnDiskAtom有两个直接子类:
        //org.apache.cassandra.db.Column
        //org.apache.cassandra.db.RangeTombstone
        //所以下面的代码分开处理
        public void serializeForSSTable(OnDiskAtom atom, DataOutput out) throws IOException
        {
            if (atom instanceof Cell)
            {
                type.columnSerializer().serialize((Cell)atom, out);
            }
            else
            {
                assert atom instanceof RangeTombstone;
                type.rangeTombstoneSerializer().serializeForSSTable((RangeTombstone)atom, out);
            }
        }

        public OnDiskAtom deserializeFromSSTable(DataInput in, Descriptor.Version version) throws IOException
        {
            return deserializeFromSSTable(in, ColumnSerializer.Flag.LOCAL, Integer.MIN_VALUE, version);
        }

        public OnDiskAtom deserializeFromSSTable(DataInput in, ColumnSerializer.Flag flag, int expireBefore, Descriptor.Version version) throws IOException
        {
            Composite name = type.serializer().deserialize(in);
            if (name.isEmpty())
            {
                // SSTableWriter.END_OF_ROW
                return null;
            }

            int b = in.readUnsignedByte();
            if ((b & ColumnSerializer.RANGE_TOMBSTONE_MASK) != 0)
                return type.rangeTombstoneSerializer().deserializeBody(in, name, version);
            else
                return type.columnSerializer().deserializeColumnBody(in, (CellName)name, b, flag, expireBefore);
        }

        public long serializedSizeForSSTable(OnDiskAtom atom)
        {
            if (atom instanceof Cell)
            {
                return type.columnSerializer().serializedSize((Cell)atom, TypeSizes.NATIVE);
            }
            else
            {
                assert atom instanceof RangeTombstone;
                return type.rangeTombstoneSerializer().serializedSizeForSSTable((RangeTombstone)atom);
            }
        }
    }
}
