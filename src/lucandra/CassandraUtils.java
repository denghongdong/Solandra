/**
 * Copyright 2009 T Jake Luciani
 * 
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
package lucandra;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.apache.cassandra.config.ConfigurationException;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.ReadCommand;
import org.apache.cassandra.db.Row;
import org.apache.cassandra.db.RowMutation;
import org.apache.cassandra.db.SliceByNamesReadCommand;
import org.apache.cassandra.db.filter.QueryPath;
import org.apache.cassandra.service.AbstractCassandraDaemon;
import org.apache.cassandra.service.StorageProxy;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.thrift.UnavailableException;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.log4j.Logger;
import org.apache.lucene.index.Term;

public class CassandraUtils
{

    public static final String               keySpace               = System.getProperty("lucandra.keyspace", "L");
    public static final String               termVecColumnFamily    = "TI";
    public static final String               docColumnFamily        = "Docs";
    public static final String               metaInfoColumnFamily   = "TL";

    public static final String               positionVectorKey      = "P";
    public static final String               offsetVectorKey        = "O";
    public static final String               termFrequencyKey       = "F";
    public static final String               normsKey               = "N";
    public static final ByteBuffer           positionVectorKeyBytes = ByteBuffer.wrap(positionVectorKey.getBytes());
    public static final ByteBuffer           offsetVectorKeyBytes   = ByteBuffer.wrap(offsetVectorKey.getBytes());
    public static final ByteBuffer           termFrequencyKeyBytes  = ByteBuffer.wrap(termFrequencyKey.getBytes());
    public static final ByteBuffer           normsKeyBytes          = ByteBuffer.wrap(normsKey.getBytes());

    public static final int                  maxDocsPerShard        = 100000;

    public static final List<Number>         emptyArray             = Arrays.asList(new Number[] { 0 });
    public static final String               delimeter              = new String("\uffff");
    public static final byte[]               delimeterBytes;

    public static final String               finalToken             = new String("\ufffe\ufffe");
    public static final ByteBuffer           finalTokenBytes;

    public static final String               documentIdField        = System.getProperty("lucandra.id.field", delimeter
                                                                            + "KEY" + delimeter);
    public static final String               documentMetaField      = delimeter + "META" + delimeter;
    public static final ByteBuffer           documentMetaFieldBytes;

    public static final boolean              indexHashingEnabled    = Boolean.valueOf(System.getProperty(
            "index.hashing", "true"));
    
  
    public static final QueryPath            metaColumnPath;


    public static final Charset UTF_8 = Charset.forName("UTF-8");

    static
    {
        try
        {
            delimeterBytes = delimeter.getBytes("UTF-8");
            documentMetaFieldBytes = ByteBuffer.wrap(documentMetaField.getBytes("UTF-8"));
            finalTokenBytes = ByteBuffer.wrap(finalToken.getBytes("UTF-8"));
            metaColumnPath = new QueryPath(CassandraUtils.docColumnFamily);
        }
        catch (UnsupportedEncodingException e)
        {
            throw new RuntimeException("UTF-8 not supported by this JVM");
        }
    }

    public static final String               hashChars              = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    public static final BigInteger           CHAR_MASK              = new BigInteger("65535");

    private static final Logger              logger                 = Logger.getLogger(CassandraUtils.class);

    private static boolean                   cassandraStarted       = false;

    // Start Cassandra up!!!
    public static synchronized void startup()
    {

        if (cassandraStarted)
            return;

        cassandraStarted = true;

        AbstractCassandraDaemon daemon = new AbstractCassandraDaemon() {

            @Override
            public void stop()
            {
                // TODO Auto-generated method stub

            }

            @Override
            public void start() throws IOException
            {
                // TODO Auto-generated method stub

            }
        };

        try
        {
            daemon.init(new String[] {});
        }
        catch (Throwable e)
        {

            e.printStackTrace();
            System.exit(2);
        }

        // Check for Lucandra schema
        // if not there then load from yaml
        if (DatabaseDescriptor.getTableDefinition(keySpace) == null)
            try
            {
                StorageService.instance.loadSchemaFromYAML();
            }
            catch (ConfigurationException e)
            {
                // TODO Auto-generated catch block
                e.printStackTrace();
                System.exit(3);
            }
            catch (IOException e)
            {
                // TODO Auto-generated catch block
                e.printStackTrace();
                System.exit(4);
            }

    }

    public static byte[] createColumnName(Term term)
    {

        return createColumnName(term.field(), term.text());
    }

    public static byte[] createColumnName(String field, String text)
    {

        // case of all terms
        if (field.equals("") || text == null)
            return delimeterBytes;

        try
        {
            return (field + delimeter + text).getBytes("UTF-8");
        }
        catch (UnsupportedEncodingException e)
        {
            throw new RuntimeException("JVM doesn't support UTF-8", e);
        }
    }

    public static Term parseTerm(String termStr)
    {

        int index = termStr.indexOf(delimeter);

        if (index < 0)
        {
            throw new RuntimeException("invalid term format: " + index + " " + termStr);
        }

        return new Term(termStr.substring(0, index), termStr.substring(index + delimeter.length()));
    }

    public static final byte[] intToByteArray(int value)
    {
        return new byte[] { (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value };
    }

    public static final int byteArrayToInt(ByteBuffer b)
    {
        logger.info(b);
        return (b.array()[b.position() + b.arrayOffset() + 0] << 24)
                + ((b.array()[b.position() + b.arrayOffset() + 1] & 0xFF) << 16)
                + ((b.array()[b.position() + b.arrayOffset() + 2] & 0xFF) << 8)
                + (b.array()[b.position() + b.arrayOffset() + 3] & 0xFF);
    }

    public static final ByteBuffer intVectorToByteArray(List<Number> intVector)
    {

        if (intVector.size() == 0)
            return FBUtilities.EMPTY_BYTE_BUFFER;

        if (intVector.get(0) instanceof Byte)
            return ByteBuffer.wrap(new byte[] { intVector.get(0).byteValue() });

        ByteBuffer buffer = ByteBuffer.allocate(4 * intVector.size());

        for (Number i : intVector)
        {
            buffer.putInt(i.intValue());
        }
        buffer.rewind();
        return buffer;
    }

    public static boolean compareByteArrays(byte[] a, byte[] b)
    {

        if (a.length != b.length)
            return false;

        for (int i = 0; i < a.length; i++)
        {
            if (a[i] != b[i])
                return false;
        }

        return true;

    }

    public static final int[] byteArrayToIntArray(ByteBuffer b)
    {

        if (b.remaining() % 4 != 0)
            throw new RuntimeException("Not a valid int array:" + b.remaining());

        int[] intArray = new int[b.remaining() / 4];
        int idx = 0;

        for (int i = b.position() + b.arrayOffset(); i < b.limit() + b.arrayOffset(); i += 4)
        {
            intArray[idx++] = (b.array()[i] << 24) + ((b.array()[i + 1] & 0xFF) << 16)
                    + ((b.array()[i + 2] & 0xFF) << 8) + (b.array()[i + 3] & 0xFF);
        }

        return intArray;
    }

    public static final byte[] encodeLong(long l)
    {
        ByteBuffer buffer = ByteBuffer.allocate(8);

        buffer.putLong(l);
        
        return buffer.array();
    }

    public static final long decodeLong(byte[] bytes)
    {

        if (bytes.length != 8)
            throw new RuntimeException("must be 8 bytes");

        ByteBuffer buffer = ByteBuffer.wrap(bytes);

        return buffer.getLong();
    }

    

    public static void addMutations(Map<ByteBuffer, RowMutation> mutationList, String columnFamily, byte[] column,
            ByteBuffer key, byte[] value, Map<ByteBuffer, List<Number>> superColumns)
    {
        addMutations(mutationList, columnFamily, ByteBuffer.wrap(column), key, ByteBuffer.wrap(value), superColumns);
    }

    public static void addMutations(Map<ByteBuffer, RowMutation> mutationList, String columnFamily, byte[] column,
            ByteBuffer key, ByteBuffer value, Map<ByteBuffer, List<Number>> superColumns)
    {
        addMutations(mutationList, columnFamily, ByteBuffer.wrap(column), key, value, superColumns);
    }

    public static void addMutations(Map<ByteBuffer, RowMutation> mutationList, String columnFamily, ByteBuffer column,
            ByteBuffer key, ByteBuffer value, Map<ByteBuffer, List<Number>> superColumns)
    {

        // Find or create row mutation
        RowMutation rm = mutationList.get(key);
        if (rm == null)
        {

            rm = new RowMutation(CassandraUtils.keySpace, key);

            mutationList.put(key, rm);
        }

        if (value == null && superColumns == null)
        { // remove

            if (column != null)
            {
                rm.delete(new QueryPath(columnFamily, column), System.currentTimeMillis());
            }
            else
            {
                rm.delete(new QueryPath(columnFamily), System.currentTimeMillis());
            }

        }
        else
        { // insert

            if (superColumns == null)
            {

                rm.add(new QueryPath(columnFamily, null, column), value, System.currentTimeMillis());

            }
            else
            {

                for (Map.Entry<ByteBuffer, List<Number>> e : superColumns.entrySet())
                {
                    rm.add(new QueryPath(columnFamily, column, e.getKey()), intVectorToByteArray(e.getValue()), System
                            .currentTimeMillis());
                }
            }
        }
    }

    public static void robustInsert(List<RowMutation> mutations, ConsistencyLevel cl)
    {

        int attempts = 0;
        while (attempts++ < 10)
        {

            try
            {
                StorageProxy.mutate(mutations, cl);
                return;
            }
            catch (UnavailableException e)
            {

            }
            catch (TimeoutException e)
            {

            }

            try
            {
                Thread.sleep(1000);
            }
            catch (InterruptedException e)
            {

            }
        }

        throw new RuntimeException("insert failed after 10 attempts");
    }

    public static List<Row> robustGet(List<ReadCommand> rc, ConsistencyLevel cl)
    {
        List<Row> rows = null;
        int attempts = 0;
        while (attempts++ < 10)
        {
            try
            {
                rows = StorageProxy.readProtocol(rc, cl);
                break;
            }
            catch (IOException e1)
            {
                throw new RuntimeException(e1);
            }
            catch (UnavailableException e1)
            {

            }
            catch (TimeoutException e1)
            {

            }
            catch (InvalidRequestException e)
            {
                throw new RuntimeException(e);
            }

            try
            {
                Thread.sleep(100);
            }
            catch (InterruptedException e)
            {

            }
        }

        if (attempts >= 10)
            throw new RuntimeException("Read command failed after 10 attempts");

        return rows;
    }

    public static List<Row> robustGet(ByteBuffer key, QueryPath qp, List<ByteBuffer> columns, ConsistencyLevel cl)
    {

        ReadCommand rc = new SliceByNamesReadCommand(CassandraUtils.keySpace, key, qp, columns);

        return robustGet(Arrays.asList(rc), cl);

    }

    /** Read the object from bytes string. */
    public static Object fromBytes(ByteBuffer data) throws IOException, ClassNotFoundException
    {

        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data.array(), data.position()+data.arrayOffset(), data
                .remaining()));
        Object o = ois.readObject();
        ois.close();
        return o;
    }

    /** Write the object to bytes. */
    public static ByteBuffer toBytes(Object o) throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(o);
        oos.close();
        return ByteBuffer.wrap(baos.toByteArray());
    }

    public static ByteBuffer hashBytes(byte[] key)
    {
        MessageDigest md;
        try
        {
            md = MessageDigest.getInstance("SHA");
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new RuntimeException(e);
        }

        int saltSize = key.length; // Only hash the indexName

        byte[] salt = new byte[saltSize];
        System.arraycopy(key, 0, salt, 0, key.length);

        md.update(salt);

        return bytesForBig(new BigInteger(1, md.digest()), 8);
    }

    public static ByteBuffer hashKeyBytes(byte[]... keys)
    {
        byte hashedKey[] = null;

        if (keys.length <= 1 || !Arrays.equals(keys[keys.length - 2], delimeterBytes))
            throw new IllegalStateException("malformed key");

        byte[] indexName = keys[0];

        if (indexHashingEnabled)
        {
            int delimiterCount = 1;
            for (int i = 0; i < keys.length - 2; i++)
            {

                if (Arrays.equals(keys[i], delimeterBytes))
                {
                    delimiterCount++;
                }
            }

            if (delimiterCount > 2)
                throw new IllegalStateException("key contains too many delimiters");

            indexName = hashBytes(indexName).array(); // safe, uses .wrap
        }

        // no hashing, just combine the arrays together
        int totalBytes = indexName.length;
        for (int i = 1; i < keys.length; i++)
            totalBytes += keys[i].length;

        hashedKey = new byte[totalBytes];
        System.arraycopy(indexName, 0, hashedKey, 0, indexName.length);
        int currentLen = indexName.length;

        for (int i = 1; i < keys.length; i++)
        {
            System.arraycopy(keys[i], 0, hashedKey, currentLen, keys[i].length);
            currentLen += keys[i].length;
        }

        return ByteBuffer.wrap(hashedKey);
    }

    // based on cassandra source
    private static ByteBuffer bytesForBig(BigInteger big, int sigchars)
    {
        byte[] chars = new byte[sigchars];

        for (int i = 0; i < sigchars; i++)
        {
            int maskpos = 16 * (sigchars - (i + 1));
            // apply bitmask and get char value
            chars[i] = (byte) hashChars.charAt(big.and(CHAR_MASK.shiftLeft(maskpos)).shiftRight(maskpos).intValue()
                    % hashChars.length());
        }

        return ByteBuffer.wrap(chars);
    }

    public static int readVInt(ByteBuffer buf)
    {
        int length = buf.remaining();

        byte b = buf.array()[buf.position() + buf.arrayOffset()];
        int i = b & 0x7F;
        for (int pos = 1, shift = 7; (b & 0x80) != 0 && pos < length; shift += 7, pos++)
        {
            b = buf.array()[buf.position() + buf.arrayOffset() + pos];
            i |= (b & 0x7F) << shift;
        }

        return i;
    }

    public static ByteBuffer writeVInt(int i)
    {
        int length = 0;
        int p = i;

        while ((p & ~0x7F) != 0)
        {
            p >>>= 7;
            length++;
        }
        length++;

        byte[] buf = new byte[length];
        int pos = 0;
        while ((i & ~0x7F) != 0)
        {
            buf[pos] = ((byte) ((i & 0x7f) | 0x80));
            i >>>= 7;
            pos++;
        }
        buf[pos] = (byte) i;

        return ByteBuffer.wrap(buf);
    }
}
