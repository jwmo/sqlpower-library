package ca.sqlpower.sql;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;

import org.apache.log4j.Logger;

/**
 * The WebResultSet is the top level of a rather deep hierarchy of classes
 * that represent (by containment, not by inheritance) a java.sql.ResulSet
 * (or, in some subclasses, multiple ResultSet objects concatenated).
 * These classes provide some of the functionality of the javax.sql.RowSet
 * (which they pre-date!), but also additional flexibility and also
 * some corrections for defects in various Drivers (mostly M$).
 * <p>
 * This class provides a no-argument constructor; if you use this, you
 * must call applyResultSet() and initMembers() yourself before calling 
 * any get/set methods (even toString()!); it is a fatal error to disobey this rule.
 * <p>
 * Some of these classes' functionality is intimately related to the
 * WebResultHTMLFormatter class.
 * 
 * @version $Id$
 */
public class WebResultSet {

	private static final Logger logger = Logger.getLogger(WebResultSet.class);

    protected ResultSet rs;
    protected String sqlQuery;
    protected ColumnFilter[] columnFilter;
    protected List[] columnChoices;
    protected List[] columnMutexList;
    protected String[] columnLabel;
    protected String[] columnChoicesName;
    protected String[] columnDefaultChoice;
    protected String[] columnDefaultValue;
    protected boolean[] columnHasAny;
    protected boolean[] columnHasAll;
    protected int[] columnType;
	protected List[] columnHyperlinks;
	protected String[] columnHyperlinkStyle;
    protected int rowidColNo;
	public String tableTitle;
	public String emptyMessage; // the message to show when the result set is empty
	protected Integer independentColumn;

	/**
	 * Keeps track of the same thing as JDBC2.0 ResultSet.isAfterLast
	 * because Microsoft's SQLServer2000 driver doesn't support this
	 * method.
	 */
	protected boolean isAfterLast;

    public WebResultSet(ResultSet results, String query) throws SQLException {
        sqlQuery=query;
		applyResultSet(results);
		initMembers(rs.getMetaData().getColumnCount());
	}

	/**
	 * A do-nothing constructor.
	 */
	protected WebResultSet() {
     super();   
    }

	/**
	 * Applies the given resultset to the current WebResultSet.  The
	 * {@link #WebResultSet(ResultSet,String)} constructor calls this
	 * method with its ResultSet argument, but the DelayedWebResultSet
	 * class doesn't call this method until its execute() method is
	 * called.  If there was a previously-applied ResultSet, its
	 * close() method is called.
	 *
	 * @param results An open JDBC ResultSet to wrap in this
	 * WebResultSet.
	 * @throws SQLException if a database error occurs.
	 */
	protected void applyResultSet(ResultSet results) throws SQLException {
		applyResultSet(results, true);
	}

	/**
	 * Applies the given resultset to the current WebResultSet.  The
	 * {@link #WebResultSet(ResultSet,String)} constructor calls this
	 * method with its ResultSet argument, but the DelayedWebResultSet
	 * class doesn't call this method until its execute() method is
	 * called.
	 *
	 * @param results An open JDBC ResultSet to wrap in this
	 * WebResultSet.
	 * @param closeOldRS If there was a previously-applied ResultSet
	 * and the <code>closeOldRS</code> argument is <code>true</code>,
	 * the old ResultSet will be closed before applying the new one.
	 * @throws SQLException if a database error occurs.
	 */
	protected void applyResultSet(ResultSet results, boolean closeOldRS) throws SQLException {
		if (closeOldRS && rs != null) {
			rs.close();
		}
        rs = results;
	}

	protected void initMembers(int cols) {
        columnFilter=new ColumnFilter[cols];
        columnChoices=new List[cols];
        columnMutexList=new List[cols];
		columnLabel=new String[cols];
        columnChoicesName=new String[cols];
        columnDefaultChoice=new String[cols];
        columnDefaultValue=new String[cols];
        columnHasAny=new boolean[cols];
        columnHasAll=new boolean[cols];
        columnType=new int[cols];
		columnHyperlinks=new List[cols];
		columnHyperlinkStyle=new String[cols];
        rowidColNo=0;
		tableTitle="";
		emptyMessage="";
		independentColumn = null;
		isAfterLast=false;
    }

    /**
     * sets the column colNo to have both special "any" and "all"
     * choices in its dropdown list of choices.
     *
     * @param colNo the column whose state should be modified
     * @param has the new value for this attribute
     * @deprecated use the separate setColumnHasAny and
     * setColumnHasAll methods instead of this composite one.
     */
    public void setColumnHasAnyAll(int colNo, boolean has) {
        columnHasAny[colNo-1]=has;
        columnHasAll[colNo-1]=has;
    }

    /**
     * gets the logical AND of this column's "any" and "all"
     * attributes.
     *
     * @return true iff columnHasAny(colNo) and columnHasAll(colNo)
     * both return true.
     * @deprecated use the separate getColumnHasAny and
     * getColumnHasAll methods instead of this composite one.
     */
    public boolean getColumnHasAnyAll(int colNo) {
        return columnHasAny[colNo-1] && columnHasAll[colNo-1];
    }

    public void setColumnHasAny(int colNo, boolean has) {
        columnHasAny[colNo-1]=has;
    }

    public boolean getColumnHasAny(int colNo) {
        return columnHasAny[colNo-1];
    }

    public void setColumnHasAll(int colNo, boolean has) {
        columnHasAll[colNo-1]=has;
    }

    public boolean getColumnHasAll(int colNo) {
        return columnHasAll[colNo-1];
    }

    /**
     * Note that filters cannot apply to Date or Numeric column types.
     */
    public void setColumnFilter(int colNo, ColumnFilter filter) {
        columnFilter[colNo-1]=filter;
    }

    public ColumnFilter getColumnFilter(int colNo) {
        return columnFilter[colNo-1];
    }

    public void setColumnChoicesList(int colNo, List choicesList) {
        columnChoices[colNo-1]=choicesList;
    }

    public List getColumnChoicesList(int colNo) {
        return columnChoices[colNo-1];
    }

    public void setColumnMutexList(int colNo, List mutexList) {
        columnMutexList[colNo-1]=mutexList;
    }

    public List getColumnMutexList(int colNo) {
        return columnMutexList[colNo-1];
    }

	public void setColumnLabel(int colNo, String label) {
		columnLabel[colNo-1]=label;
	}

    public void setColumnChoicesName(int colNo, String choicesName) {
        columnChoicesName[colNo-1]=choicesName;
    }

    public String getColumnChoicesName(int colNo)
        throws ColumnNotDisplayableException {
        if(colNo == rowidColNo) {
            throw new ColumnNotDisplayableException();
        } else {
            return columnChoicesName[colNo-1];
        }
    }

    /**
     * Sets the default choice for the USER INPUT ELEMENT associated
     * with this column.
     */
    public void setColumnDefaultChoice(int colNo, String defaultChoice) {
        columnDefaultChoice[colNo-1]=defaultChoice;
    }

    /**
     * Gets the default choice for the USER INPUT ELEMENT associated
     * with this column.
     */
    public String getColumnDefaultChoice(int colNo)
        throws ColumnNotDisplayableException {
        if(colNo == rowidColNo) {
            throw new ColumnNotDisplayableException();
        } else {
            return columnDefaultChoice[colNo-1];
        }
    }

    /**
     * Sets the default value EXPECTED FROM THE DATABASE in this
     * column.  Mismatching values will be highlighted in red. A value
     * of null disables this comparison.
     */
    public void setColumnDefaultValue(int colNo, String defaultValue) {
        columnDefaultValue[colNo-1]=defaultValue;
    }

    /**
     * Gets the default value EXPECTED FROM THE DATABASE in this
     * column.
     */
    public String getColumnDefaultValue(int colNo) {
        return columnDefaultValue[colNo-1];
    }

    /**
     * don't use this.
     *
     * @deprecated Set column 1 to have a type of FieldTypes.ROWID
     * instead of using this function.
     */
    public void setShowFirstColumn(boolean flag) {
        if(flag) {
            setColumnType(1, FieldTypes.ROWID);
        } else {
            setColumnType(1, FieldTypes.ALPHANUM_CODE);
            rowidColNo=0;
        }
    }

    /**
     * don't use this.
     *
     * @deprecated Check if column 1 has type FieldTypes.ROWID instead
     * of using this function.
     */
    public boolean getShowFirstColumn() {
        return(getColumnType(1) != FieldTypes.ROWID);
    }
    
    /**
     * Gets the value of columnType[].  See 
     * {@link ca.sqlpower.sql.FieldTypes} for valid types.
     * i is 1-based.
     *
     * @param colNo The (1-based) column number to get the type of.
     * @return value of the ith column's type.
     */
    public int getColumnType(int colNo) {
        return columnType[colNo-1];
    }
    
    /**
     * Sets the value of the ith column's columnType.  See
     * {@link ca.sqlpower.sql.FieldTypes} for valid types.
     * i is 1-based.
     *
     * @param colNo The (1-based) column number to set the type of.
     * @param v Value to assign to the ith column's type.
     */
    public void setColumnType(int colNo, int  v) {
		if (logger.isDebugEnabled()) logger.debug("Setting column "+colNo+" to type "+v);
        if (v==FieldTypes.ROWID) {
            if (rowidColNo > 0) {
                throw new IllegalStateException("A resultset can have only one ROWID column");
            }
            rowidColNo=colNo;
        }
        this.columnType[colNo-1] = v;   
    }

	/**
	 * Returns the list of hyperlinks for the given column.  Elements
	 * of this list should be of type ca.sqlpower.util.Hyperlink.
	 *
	 * @param colNo The column number in question.
	 * @return The hyperlink list for this column (<code>null</code>
	 * if no text has been specified).
	 */
	public List getColumnHyperlinks(int colNo) {
		return this.columnHyperlinks[colNo-1];
	}

	/**
	 * Sets the list of hyperlinks for each entry in this column.  The
	 * entries of the list must all be of type
	 * <code>ca.sqlpower.util.Hyperlink</code>, although no type-checking is done
	 * here.<p>
	 *
	 * The WebResultFormatter will output one HTML hyperlink per list
	 * entry for each row in the resultset. The hyperlink's
	 * <code>text</code> and <code>href</code> values are both used as
	 * a pattern in a <code>LongMessageFormat</code>.  The escape
	 * sequences (for example, <code>{3} {4}</code> in the strings
	 * correspond with values in the current row of the resultset.
	 * <code>{1}</code> corresponds with the first column in the
	 * resultset, <code>{2}</code> with the second, and so on.
	 * <code>{0}</code> is a placeholder, and is always
	 * <code>null</code>.
	 *
	 * @param colNo The column number to which the href text applies.
	 * @param links The list of hyperlinks to render in this column.
	 *
	 * @see ca.sqlpower.util.Hyperlink
	 * @see ca.sqlpower.util.LongMessageFormat
	 */
	public void setColumnHyperlinks(int colNo, List links) {
		this.columnHyperlinks[colNo-1]=links;
	}

	/**
	 * Sets the CSS style class name that should be applied to the hyperlink column.
	 */
	public void setColumnHyperlinkStyle(int colNo, String style) {
		this.columnHyperlinkStyle[colNo-1]=style;
	}

	/**
	 * Gets the CSS style class name that should be applied to the hyperlink column.
	 */
	public String getColumnHyperlinkStyle(int colNo) {
		return this.columnHyperlinkStyle[colNo-1];
	}
    
    public String getSqlQuery() {
        return sqlQuery;
    }

    public int getColumnCount() throws SQLException {
        return rs.getMetaData().getColumnCount();
    }

	/**
	 * Returns the label which was set for this column using {@link
	 * #setColumnLabel(int,String)}, or the default column label from
	 * the SQL query if no user-defined label was previously set.
	 */
    public String getColumnLabel(int colNo)
        throws SQLException, ColumnNotDisplayableException {
        if(colNo == rowidColNo) {
            throw new ColumnNotDisplayableException();
        } else {
            if(columnLabel[colNo-1] != null) {
                return columnLabel[colNo-1];
            } else {
                return rs.getMetaData().getColumnLabel(colNo);
            }
        }
    }

	/**
	 * Always gives back the original column name from the SQL query,
	 * not the user-supplied label.
	 *
	 * @return The column label as defined in the underlying
	 * <code>ResultSetMetaData</code>.
	 */
	public String getColumnName(int colNo) throws SQLException {
		return rs.getMetaData().getColumnLabel(colNo);
	}

    /**
     * retrieves the current row's unique identifier (the one having
     * the row type of "FieldTypes.ROWID").
     *
     * @return the current row's unique identifier
     * @throws NoRowidException if no column is of type
     * FieldTypes.ROWID
     * @throws SQLException if there is a database error retrieving
     * the current row identifier.
     */
    public String getRowid() throws SQLException, NoRowidException {
        if(rowidColNo>0) {
            return rs.getString(rowidColNo);
        } else {
            throw new NoRowidException();
        }
    }

    public String toString() {
        StringBuffer sb=new StringBuffer(1024);
        int numCols=0;

        try {
            numCols=getColumnCount();
        } catch(SQLException e) {
            sb.append("SQL Exception while getting column count!");
        }

        for(int i=1; i<=numCols; i++) {
            try {
                sb.append("Column ")
                    .append(i)
                    .append(": type ")
                    .append(getColumnType(i))
                    .append(", label \"")
                    .append(getColumnLabel(i))
                    .append("\"\n");
            } catch(SQLException e) {
                sb.append("SQLException processing column!");
            } catch(ColumnNotDisplayableException e) {
                sb.append("Column not displayable!");
            }
        }
        return sb.toString();
    }

    public String getTableTitle(){
		return tableTitle;
	}

	public void setTableTitle(String title){
		tableTitle=title;
	}

    public String getEmptyMessage(){
		return emptyMessage;
	}

	public void setEmptyMessage(String message){
		emptyMessage=message;
	}


	/**
	 * Returns the column number representing the independent variable
	 * for graphing or charting the resultset.
	 *
	 * @return the column number
	 * @throws IllegalStateException if no independant column was
	 * previously specified.
	 */ 
	public int getIndependentColNo() {
		if (this.independentColumn == null) {
			throw new IllegalStateException("no independent column was specified.");
		} else {
			return this.independentColumn.intValue();
		}
	}
	
	/**
	 * Sets the value of independentColumn.
	 *
	 * @param argIndependentColumn Value to assign to this.independentColumn
	 */
	public void setIndependentColNo(int argIndependentColumn){
		this.independentColumn = new Integer(argIndependentColumn);
	}

	/**
	 * Returns the value for this row which is stored in the
	 * independant column.
	 *
	 * @throws SQLException if a database error occurs.  This
	 * documentation is of no value.
	 * @throws IllegalStateException if no independant column was
	 * previously specified.
	 */
	public String getIndependentField() throws SQLException, IllegalStateException {
		return getString(getIndependentColNo());
	}

	// ****************************************
    // EXPOSED RESULTSET METHODS ARE BELOW HERE
	// ****************************************

    public boolean next() throws SQLException {
		boolean hasMoreRows = rs.next();
		if(!hasMoreRows) {
			isAfterLast = true;
		}
        return hasMoreRows;
    }

    /**
	 * Never use this.
	 *
     * @deprecated Use the getString(int) method instead of this one,
     * or don't use a WebResultSet.  String-based column specification
     * does not support the columnFilter feature.  Subclasses that
     * supply other special behaviours such as derived columns,
     * security, and more will also be incompatible with this method.
     */
    public String getString(String colName) throws SQLException {
        return rs.getString(colName);
    }

    public String getString(int colNo) throws SQLException {
        if(columnFilter[colNo-1]!=null) {
            return columnFilter[colNo-1].filter(rs.getString(colNo));
        }
        return rs.getString(colNo);
    }

	/**
	 * Returns a Java object (object type mapping is the default)
	 * which represents the value of the current record's given
	 * column.
	 *
	 * @param colNo The column number.  The first column is number 1,
	 * not 0. 0 is invalid.
	 */
	public Object getObject(int colNo) throws SQLException {
		return rs.getObject(colNo);
	}

    /**
	 * Never use this.
	 *
     * @deprecated Use the getObject(int) method instead of this one,
     * or don't use a WebResultSet.  String-based column specification
     * does not support the columnFilter feature.  Subclasses that
     * supply other special behaviours such as derived columns,
     * security, and more will also be incompatible with this method.
     */
    public Object getObject(String colName) throws SQLException {
        return rs.getObject(colName);
    }

    /**
	 * Never use this.
	 *
     * @deprecated Use the getDouble(int) method instead of this one,
     * or don't use a WebResultSet.  String-based column specification
     * does not support the columnFilter feature.  Subclasses that
     * supply other special behaviours such as derived columns,
     * security, and more will also be incompatible with this method.
     */
    public Double getDouble(String colName) throws SQLException {
         double val =rs.getDouble(colName);
		if (rs.wasNull()) {
			return null;
		} else {
			return new Double(val);
		}
    }

	/**
	 * Returns a Double of the given column on the current row.
	 */
    public Double getDouble(int colNo) throws SQLException {
        double val = rs.getDouble(colNo);
		if (rs.wasNull()) {
			return null;
		} else {
			return new Double(val);
		}
    }

	/**
	 * Gets the date out of the database by first getting it as a
	 * string, then parsing the first 10 characters in yyyy-MM-dd
	 * format, and returning the parsed date.  I have no idea why it
	 * doesn't use the underlying ResultSet.getDate(int) method, but
	 * there must be a good reason...
	 *
	 * <p>I think it might be because of a funny timezone problem in
	 * oracle 8i.
	 */
    public java.sql.Date getDate(int colNo) throws SQLException {
    	String dateStr = rs.getString(colNo);
		if (dateStr == null) {
			return null;
		}
    	SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
		java.util.Date date = null;
		try {
	    	date = sdf.parse(dateStr.substring(0,10));
		} catch (ParseException e) {
			throw new SQLException("invalid date format in column "+colNo);
		}
        return new java.sql.Date(date.getTime());
    }

	/**
	 * Just returns <code>rs.getTimestamp(colNo)</code>.  Does not do
	 * the funny string-date conversion stuff like {@link #getDate}
	 * does.
	 */
	public Timestamp getTimestamp(int colNo) throws SQLException {
		return rs.getTimestamp(colNo);
	}

    public float getFloat(int colNo) throws SQLException {
        return rs.getFloat(colNo);
    }

    public int getInt(int colNo) throws SQLException {
        return rs.getInt(colNo);
    }

	public boolean isAfterLast() throws SQLException {
		return isAfterLast;
	}

    /**
     * Closes the JDBC ResultSet's Statement object, thereby freeing
     * the database cursor.  Cursors are a limited resource, so it is
     * important to do this explicitly rather than waiting for garbage
     * collection.
     */
    public void close() throws SQLException {
        rs.getStatement().close();
    }

	/**
	 * Returns the current result set's metadata.
	 */
	public ResultSetMetaData getRsmd() throws SQLException {
		return rs.getMetaData();
	}

}
