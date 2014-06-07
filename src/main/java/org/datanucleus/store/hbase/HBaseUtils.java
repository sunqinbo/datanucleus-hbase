/**********************************************************************
Copyright (c) 2009 Erik Bengtson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors :
2011 Andy Jefferson - add family/column getters for datastore id, version, embedded fields
2011 Andy Jefferson - extended schema creation, and added schema deletion
 ...
***********************************************************************/
package org.datanucleus.store.hbase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.sql.Timestamp;
import java.util.Date;

import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.datanucleus.ExecutionContext;
import org.datanucleus.NucleusContext;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.identity.IdentityUtils;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.ColumnMetaData;
import org.datanucleus.metadata.EmbeddedMetaData;
import org.datanucleus.metadata.IdentityType;
import org.datanucleus.metadata.VersionMetaData;
import org.datanucleus.metadata.VersionStrategy;
import org.datanucleus.state.ObjectProvider;
import org.datanucleus.store.StoreManager;
import org.datanucleus.store.schema.table.Table;
import org.datanucleus.store.types.converters.TypeConverter;

public class HBaseUtils
{
    /**
     * Accessor for the default value specified for the provided member.
     * If no defaultValue is provided on the column then returns null.
     * @param mmd Metadata for the member
     * @return The default value
     */
    public static String getDefaultValueForMember(AbstractMemberMetaData mmd)
    {
        ColumnMetaData[] colmds = mmd.getColumnMetaData();
        if (colmds == null || colmds.length < 1)
        {
            return null;
        }
        return colmds[0].getDefaultValue();
    }

    /**
     * Accessor for the family name for a column name.
     * If the column name is of the form "family:qualifier" then returns family, otherwise returns the table name.
     * @param colName Column name
     * @param tableName The table name
     * @return The family name for this column
     */
    public static String getFamilyNameForColumnName(String colName, String tableName)
    {
        if (colName != null && colName.indexOf(":") > 0)
        {
            return colName.substring(0, colName.indexOf(":"));
        }
        return tableName;
    }

    /**
     * Accessor for the qualifier name for a column name.
     * If the column name is of the form "family:qualifier" then returns qualifier, otherwise returns the column name.
     * @param colName Column name
     * @return The qualifier name for this column
     */
    public static String getQualifierNameForColumnName(String colName)
    {
        if (colName != null && colName.indexOf(":") > 0)
        {
            return colName.substring(colName.indexOf(":") + 1);
        }
        return colName;
    }

    /**
     * Accessor for the HBase family name for the field of the embedded field.
     * Extracts the family name using the following priorities
     * <ul>
     * <li>If column is specified as "a:b" then takes "a" as the family name.</li>
     * <li>Otherwise takes table name as the family name</li>
     * </ul>
     * @param mmd Metadata for the embedded field
     * @param fieldNumber Number of the field in the embedded object
     * @param tableName Name of the table
     * @return The family name
     */
    public static String getFamilyName(AbstractMemberMetaData mmd, int fieldNumber, String tableName)
    {
        // Try from the column name if specified as "a:b"
        EmbeddedMetaData embmd = mmd.getEmbeddedMetaData();
        AbstractMemberMetaData embMmd = null;
        if (embmd != null)
        {
            AbstractMemberMetaData[] embmmds = embmd.getMemberMetaData();
            embMmd = embmmds[fieldNumber];
            ColumnMetaData[] colmds = embMmd.getColumnMetaData();
            if (colmds != null && colmds.length > 0)
            {
                return getFamilyNameForColumnName(colmds[0].getName(), tableName);
            }
        }

        // Fallback to table name
        return tableName;
    }

    /**
     * Accessor for the HBase qualifier name for the field of this embedded field.
     * Extracts the qualifier name using the following priorities
     * <ul>
     * <li>If column is specified as "a:b" then takes "b" as the qualifier name.</li>
     * <li>Otherwise takes the column name as the qualifier name when it is specified</li>
     * <li>Otherwise takes "VERSION" as the qualifier name</li>
     * </ul>
     * @param mmd Metadata for the owning member
     * @param fieldNumber Member number of the embedded object
     * @return The qualifier name
     */
    public static String getQualifierName(AbstractMemberMetaData mmd, int fieldNumber)
    {
        String columnName = null;

        // Try the first column if specified
        EmbeddedMetaData embmd = mmd.getEmbeddedMetaData();
        AbstractMemberMetaData embMmd = null;
        if (embmd != null)
        {
            AbstractMemberMetaData[] embmmds = embmd.getMemberMetaData();
            embMmd = embmmds[fieldNumber];
        }

        if (embMmd != null)
        {
            ColumnMetaData[] colmds = embMmd.getColumnMetaData();
            if (colmds != null && colmds.length > 0)
            {
                columnName = colmds[0].getName();
            }
            if (columnName == null)
            {
                // Fallback to the field/property name
                columnName = embMmd.getName();
            }
        }
        return getQualifierNameForColumnName(columnName);
    }

    /**
     * Accessor for the HBase family name for this field. Extracts the family name using the following priorities
     * <ul>
     * <li>If column is specified as "a:b" then takes "a" as the family name.</li>
     * <li>Otherwise takes the table name as the family name</li>
     * </ul>
     * @param acmd Metadata for the class
     * @param absoluteFieldNumber Field number
     * @param tableName Name of the table
     * @return The family name
     */
    public static String getFamilyName(AbstractClassMetaData acmd, int absoluteFieldNumber, String tableName)
    {
        AbstractMemberMetaData ammd = acmd.getMetaDataForManagedMemberAtAbsolutePosition(absoluteFieldNumber);
        String columnName = null;

        // Try the first column if specified
        ColumnMetaData[] colmds = ammd.getColumnMetaData();
        if (colmds != null && colmds.length > 0)
        {
            columnName = colmds[0].getName();
            if (columnName != null && columnName.indexOf(":") > -1)
            {
                return columnName.substring(0, columnName.indexOf(":"));
            }
        }

        // Fallback to the table name
        return tableName;
    }

    /**
     * Accessor for the HBase qualifier name for this field. Extracts the qualifier name using the following priorities
     * <ul>
     * <li>If column is specified as "a:b" then takes "b" as the qualifier name.</li>
     * <li>Otherwise takes the column name as the qualifier name when it is specified</li>
     * <li>Otherwise takes the field name as the qualifier name</li>
     * </ul>
     * @param acmd Metadata for the class
     * @param absoluteFieldNumber Field number
     * @return The qualifier name
     */
    public static String getQualifierName(AbstractClassMetaData acmd, int absoluteFieldNumber)
    {
        AbstractMemberMetaData ammd = acmd.getMetaDataForManagedMemberAtAbsolutePosition(absoluteFieldNumber);
        String columnName = null;

        // Try the first column if specified
        ColumnMetaData[] colmds = ammd.getColumnMetaData();
        if (colmds != null && colmds.length > 0)
        {
            columnName = colmds[0].getName();
        }
        if (columnName == null)
        {
            // Fallback to the field/property name
            columnName = ammd.getName();
        }
        if (columnName.indexOf(":") > -1)
        {
            columnName = columnName.substring(columnName.indexOf(":") + 1);
        }
        return columnName;
    }

    /**
     * Convenience method that extracts the version for a class of the specified type from the passed Result.
     * @param cmd Metadata for the class
     * @param result The result
     * @param ec ExecutionContext
     * @param tableName Name of the table
     * @param storeMgr StoreManager
     * @return The version
     */
    public static Object getVersionForObject(AbstractClassMetaData cmd, Result result, ExecutionContext ec,
            String tableName, StoreManager storeMgr)
    {
        if (cmd.isVersioned())
        {
            VersionMetaData vermd = cmd.getVersionMetaDataForClass();
            if (vermd.getFieldName() != null)
            {
                // Version stored in a field
                AbstractMemberMetaData verMmd = cmd.getMetaDataForMember(vermd.getFieldName());
                String familyName = HBaseUtils.getFamilyName(cmd, verMmd.getAbsoluteFieldNumber(), tableName);
                String qualifName = HBaseUtils.getQualifierName(cmd, verMmd.getAbsoluteFieldNumber());
                Object version = null;
                try
                {
                    byte[] bytes = result.getValue(familyName.getBytes(), qualifName.getBytes());
                    if (vermd.getVersionStrategy() == VersionStrategy.VERSION_NUMBER)
                    {
                        if (verMmd.getType() == Integer.class || verMmd.getType() == int.class)
                        {
                            // Field is integer based so use that
                            version = Bytes.toInt(bytes);
                        }
                        else
                        {
                            // Assume using Long
                            version = Bytes.toLong(bytes);
                        }
                    }
                    else if (Date.class.isAssignableFrom(verMmd.getType()))
                    {
                        // Field is of type Date (hence persisted as String), but version needs to be Timestamp
                        String strValue = new String(bytes);
                        TypeConverter strConv = ec.getTypeManager().getTypeConverterForType(verMmd.getType(), String.class);
                        version = strConv.toMemberType(strValue);
                        version = new Timestamp(((Date)version).getTime());
                    }
                    else
                    {
                        ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                        ObjectInputStream ois = new ObjectInputStream(bis);
                        version = ois.readObject();
                        ois.close();
                        bis.close();
                    }
                }
                catch (Exception e)
                {
                    throw new NucleusException(e.getMessage(), e);
                }
                return version;
            }
            else
            {
                return getSurrogateVersionForObject(cmd, result, tableName, storeMgr);
            }
        }
        return null;
    }

    /**
     * Convenience method that extracts the surrogate version for a class of the specified type from
     * the passed Result.
     * @param cmd Metadata for the class
     * @param result The result
     * @param tableName Name of the table
     * @param storeMgr Store Manager
     * @return The surrogate version
     */
    public static Object getSurrogateVersionForObject(AbstractClassMetaData cmd, Result result, String tableName, StoreManager storeMgr)
    {
        Table table = storeMgr.getStoreDataForClass(cmd.getFullClassName()).getTable();
        VersionMetaData vermd = cmd.getVersionMetaDataForClass();
        String colName = table.getVersionColumn().getName();
        String familyName = HBaseUtils.getFamilyNameForColumnName(colName, tableName);
        String qualifName = HBaseUtils.getQualifierNameForColumnName(colName);
        Object version = null;
        try
        {
            byte[] bytes = result.getValue(familyName.getBytes(), qualifName.getBytes());
            if (vermd.getVersionStrategy() == VersionStrategy.VERSION_NUMBER)
            {
                version = Bytes.toLong(bytes);
            }
            else
            {
                ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                ObjectInputStream ois = new ObjectInputStream(bis);
                version = ois.readObject();
                ois.close();
                bis.close();
            }
        }
        catch (Exception e)
        {
            throw new NucleusException(e.getMessage(), e);
        }
        return version;
    }

    public static Put getPutForObject(ObjectProvider op) throws IOException
    {
        byte[] rowKey = (byte[]) op.getAssociatedValue("HBASE_ROW_KEY");
        if (rowKey == null)
        {
            AbstractClassMetaData cmd = op.getClassMetaData();
            final Object[] pkValues = findKeyObjects(op, cmd);
            ExecutionContext ec = op.getExecutionContext();
            if (ec.getStatistics() != null)
            {
                // Add to statistics
                ec.getStatistics().incrementNumReads();
            }
            rowKey = getRowKeyForPkValue(pkValues, ec.getNucleusContext());
        }
        return new Put(rowKey);
    }

    public static Delete getDeleteForObject(ObjectProvider op) throws IOException
    {
        byte[] rowKey = (byte[]) op.getAssociatedValue("HBASE_ROW_KEY");
        if (rowKey == null)
        {
            AbstractClassMetaData cmd = op.getClassMetaData();
            final Object[] pkValues = findKeyObjects(op, cmd);
            ExecutionContext ec = op.getExecutionContext();
            if (ec.getStatistics() != null)
            {
                // Add to statistics
                ec.getStatistics().incrementNumReads();
            }
            rowKey = getRowKeyForPkValue(pkValues, ec.getNucleusContext());
        }
        return new Delete(rowKey);
    }

    public static Get getGetForObject(ObjectProvider op) throws IOException
    {
        byte[] rowKey = (byte[]) op.getAssociatedValue("HBASE_ROW_KEY");
        if (rowKey == null)
        {
            AbstractClassMetaData cmd = op.getClassMetaData();
            final Object[] pkValues = findKeyObjects(op, cmd);
            ExecutionContext ec = op.getExecutionContext();
            if (ec.getStatistics() != null)
            {
                // Add to statistics
                ec.getStatistics().incrementNumReads();
            }
            rowKey = getRowKeyForPkValue(pkValues, ec.getNucleusContext());
        }
        return new Get(rowKey);
    }

    public static Result getResultForObject(ObjectProvider op, HTableInterface table) throws IOException
    {
        Get get = getGetForObject(op);
        return table.get(get);
    }

    public static boolean objectExistsInTable(ObjectProvider op, HTableInterface table) throws IOException
    {
        Get get = getGetForObject(op);
        return table.exists(get);
    }

    private static Object[] findKeyObjects(final ObjectProvider op, final AbstractClassMetaData cmd)
    {
        if (cmd.getIdentityType() == IdentityType.DATASTORE)
        {
            return new Object[]{IdentityUtils.getTargetKeyForDatastoreIdentity(op.getInternalObjectId())};
        }
        else if (cmd.getIdentityType() == IdentityType.APPLICATION)
        {
            final int[] fieldNumbers = op.getClassMetaData().getPKMemberPositions();
            final Object[] keyObjects = new Object[fieldNumbers.length];
            for (int i = 0; i < fieldNumbers.length; i++)
            {
                keyObjects[i] = op.provideField(fieldNumbers[i]);
            }
            return keyObjects;
        }
        else
        {
            final int[] fieldNumbers = op.getClassMetaData().getAllMemberPositions();
            final Object[] keyObjects = new Object[fieldNumbers.length];
            for (int i = 0; i < fieldNumbers.length; i++)
            {
                keyObjects[i] = op.provideField(fieldNumbers[i]);
            }
            return keyObjects;
        }
    }

    /**
     * Convenience method to generate the row key given the provided PK value(s).
     * @param pkValues Values of the PK field(s)
     * @param nucCtx NucleusContext
     * @return The row key
     * @throws IOException If an error occurs generating the row key
     */
    static byte[] getRowKeyForPkValue(Object[] pkValues, NucleusContext nucCtx) throws IOException
    {
        boolean useSerialisation = nucCtx.getConfiguration().getBooleanProperty("datanucleus.hbase.serialisedPK");
        if (pkValues.length == 1 && !useSerialisation)
        {
            Object pkValue = pkValues[0];
            if (pkValue instanceof String)
            {
                return Bytes.toBytes((String) pkValue);
            }
            else if (pkValue instanceof Long)
            {
                return Bytes.toBytes(((Long) pkValue).longValue());
            }
            else if (pkValue instanceof Integer)
            {
                return Bytes.toBytes(((Integer) pkValue).intValue());
            }
            else if (pkValue instanceof Short)
            {
                return Bytes.toBytes(((Short) pkValue).shortValue());
            }
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try
        {
            if (useSerialisation)
            {
                ObjectOutputStream oos = new ObjectOutputStream(bos);
                try
                {
                    // Legacy data, up to and including DN 3.0.1
                    for (Object pkValue : pkValues)
                    {
                        oos.writeObject(pkValue);
                    }
                }
                finally
                {
                    oos.close();
                }
            }
            else
            {
                for (Object pkValue : pkValues)
                {
                    if (pkValue instanceof String)
                    {
                        bos.write(Bytes.toBytes((String) pkValue));
                    }
                    else if (pkValue instanceof Long)
                    {
                        bos.write(Bytes.toBytes(((Long) pkValue).longValue()));
                    }
                    else if (pkValue instanceof Integer)
                    {
                        bos.write(Bytes.toBytes(((Integer) pkValue).intValue()));
                    }
                    else if (pkValue instanceof Short)
                    {
                        bos.write(Bytes.toBytes(((Short) pkValue).shortValue()));
                    }
                    else
                    {
                        // Object serialisation approach. Can't keep that open from the very
                        // beginning, it messes up the byte stream.
                        ObjectOutputStream oos = new ObjectOutputStream(bos);
                        try
                        {
                            oos.writeObject(pkValue);
                        }
                        finally
                        {
                            oos.close();
                        }
                    }
                }
            }
            return bos.toByteArray();
        }
        finally
        {
            bos.close();
        }
    }
}