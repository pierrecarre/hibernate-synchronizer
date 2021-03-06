package com.hudson.hibernatesynchronizer.db;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.hudson.hibernatesynchronizer.util.HSUtil;
import com.hudson.hibernatesynchronizer.util.IErrorHandler;


public class MetaDataRetriever extends Thread {

	private Container container;
	private DatabaseMetaData metadata;
	private IErrorHandler errorHandler;

	public MetaDataRetriever (Container container, DatabaseMetaData metadata, IErrorHandler errorHandler) {
		this.container = container;
		this.metadata = metadata;
	}

	public void run() {
		try {
			populateTableData(metadata, container);
		}
		catch (Exception e) {
			errorHandler.onError(null, e);
		}
		finally {
			try {
				if (null != metadata && null != metadata.getConnection()) metadata.getConnection().close();
			}
			catch (SQLException sqle) {}
		}
	}

	/**
	 * Return the number of tables that match the schema pattern and table pattern given
	 * @param metadata
	 * @param schemaPattern
	 * @param tablePattern
	 * @return the number of tables
	 * @throws SQLException
	 */
	public static int getTableCount (DatabaseMetaData metadata, String schemaPattern, String tablePattern) throws SQLException {
		String[] names = {"TABLE"};
		String _schema = null;
		String _table = null;
		if (null != schemaPattern && schemaPattern.trim().length() > 0)
			_schema = schemaPattern.trim();
		else
			_schema=null;
		if (null != tablePattern && tablePattern.trim().length() > 0)
			_table = tablePattern.trim();
		else
			_table=null;
		ResultSet rs = null;
		try {
			int count = 0;
			rs = metadata.getTables(null, _schema, _table, names);
			Map tables = new HashMap();
			String TABLE_NAME = "TABLE_NAME";
			String TABLE_TYPE = "TABLE_TYPE";
			String SYSTEM = "SYSTEM";
			while (rs.next()) { 
				String tableType = rs.getString(TABLE_TYPE);
				if (null == tableType || tableType.toUpperCase().indexOf(SYSTEM) < 0) {
					count++;
				}
			}
			return count;
		}
		finally {
			if (null != rs) rs.close();
		}
	}

	/**
	 * Load the container with table objects that only contain the name.
	 * Start the thread or call populateTableData to load the columns and keys.
	 * @param container
	 * @param metadata
	 * @param schemaPattern
	 * @param tablePattern
	 * @throws SQLException
	 */
	public static void getTables (Container container, DatabaseMetaData metadata, String schemaPattern, String tablePattern) throws SQLException {
		String[] names = {"TABLE"};
		String _schema = null;
		String _table = null;
		if (null != schemaPattern && schemaPattern.trim().length() > 0)
			_schema = schemaPattern.trim();
		else
			_schema = null;
		if (null != tablePattern && tablePattern.trim().length() > 0)
			_table = tablePattern.trim();
		else
			_table = null;
		ResultSet rs = null;
		try {
			rs = metadata.getTables(null, _schema, _table, names);
			Map tables = new HashMap();
			String TABLE_NAME = "TABLE_NAME";
			String TABLE_TYPE = "TABLE_TYPE";
			String SYSTEM = "SYSTEM";
			while (rs.next()) { 
				DBTable table = new DBTable(container, rs.getString(TABLE_NAME)); 
				String tableType = rs.getString(TABLE_TYPE);
				if (null == tableType || tableType.toUpperCase().indexOf(SYSTEM) < 0) {
					tables.put(table.getName(), table);
				}
			}
			container.setTables(tables);
		}
		finally {
			if (null != rs) rs.close();
		}
	}

	/**
	 * Populate the column and key information for the tables.
	 * @param metadata
	 * @param container the table container
	 * @throws SQLException
	 */
	public static void populateTableData (DatabaseMetaData metadata, Container container) throws SQLException {
		Map tables = container.getTables();
		for (Iterator i=tables.values().iterator(); i.hasNext(); ) {
			DBTable table = (DBTable) i.next();
			readTableColumns(metadata, table);
		}
		for (Iterator i=tables.values().iterator(); i.hasNext(); ) {
			DBTable table = (DBTable) i.next();
			readTableKeys(metadata, table, tables);
		}
		for (Iterator i=tables.values().iterator(); i.hasNext(); ) {
			DBTable table = (DBTable) i.next();
			table.init();
		}
		container.fullyLoaded = true;
	}

	/**
	 * Read the columns from the DatabaseMetaData and notify the given table of the colums
	 * @param meta
	 * @param table
	 * @throws SQLException
	 */
	private static void readTableColumns(DatabaseMetaData meta, DBTable table) throws SQLException { 
		ResultSet columns = null;
		try {
			columns = meta.getColumns(null, "%", table.getName(), "%"); 
			while (columns.next()) { 
				String columnName = columns.getString("COLUMN_NAME"); 
				String datatype = columns.getString("TYPE_NAME"); 
				int datasize = columns.getInt("COLUMN_SIZE"); 
				int digits = columns.getInt("DECIMAL_DIGITS"); 
				int nullable = columns.getInt("NULLABLE"); 
				DBColumn newColumn = new DBColumn(table, columnName, datatype, datasize, digits, nullable);
				table.notifyColumn(newColumn);
			}
		}
		finally {
			if (null != columns) columns.close();
		}
	}

	/**
	 * Read the primary and foreign keys from the DatabaseMetaData and notify the given table of the keys
	 * @param meta
	 * @param table
	 * @param tables
	 * @throws SQLException
	 */
	private static void readTableKeys(DatabaseMetaData meta, DBTable table, Map tables) throws SQLException {
		ResultSet keys = null;
		try {
			// primary keys
			keys = meta.getPrimaryKeys(null, null, table.getName());
			while (keys.next()) {
				String tableName = keys.getString("TABLE_NAME");
				String columnName = keys.getString("COLUMN_NAME");
				table = (DBTable) tables.get(checkName(tableName));
				table.notifyPrimaryKey(checkName(columnName));
			}

			// foreign keys
			keys = meta.getImportedKeys(null, null, table.getName());
			List rels = new ArrayList();
			while (keys.next()) {
				String pkTableName = keys.getString("PKTABLE_NAME");
				pkTableName = keys.getString("PKTABLE_NAME");
				String pkColumnName = keys.getString("PKCOLUMN_NAME");
				String fkTableName = keys.getString("FKTABLE_NAME");
				String fkColumnName = keys.getString("FKCOLUMN_NAME");
				DBTable pkTable = (DBTable) tables.get(checkName(pkTableName));
				DBTable fkTable = (DBTable) tables.get(checkName(fkTableName));
				if (null != pkTable && null != fkTable) {
					DBColumn pkColumn = pkTable.getColumn(checkName(pkColumnName));
					if (null != pkColumn)
						table.notifyForeignKey(checkName(fkColumnName), pkColumn);
				}
			}
		}
		finally {
			if (null != keys) keys.close();
		}
	}
	
	private static String checkName (String s) {
		if (null == s) return null;
		s = HSUtil.stringReplace(s, "`", "");
		return s;
	}
}