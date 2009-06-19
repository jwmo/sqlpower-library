/*
 * Copyright (c) 2008, SQL Power Group Inc.
 *
 * This file is part of Wabit.
 *
 * Wabit is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * Wabit is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
 */

package ca.sqlpower.query;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.log4j.Logger;

import ca.sqlpower.graph.DepthFirstSearch;
import ca.sqlpower.graph.GraphModel;
import ca.sqlpower.sql.JDBCDataSource;
import ca.sqlpower.sqlobject.SQLDatabase;
import ca.sqlpower.sqlobject.SQLDatabaseMapping;
import ca.sqlpower.sqlobject.SQLTable;

/**
 * This class will cache all of the parts of a select
 * statement and also listen to everything that could
 * change the select statement.
 */
public class Query {
	
	private static final Logger logger = Logger.getLogger(Query.class);
	
    /**
     * If the row limit changes causing the result set cache to become empty
     * a change event will fire with this property.
     */
    protected static final String ROW_LIMIT = "rowLimit";
	
	/**
	 * A property name that is thrown in PropertyChangeListeners when part of
	 * the query has changed. This is a generic default change to a query
	 * rather than a specific query change.
	 */
	public static final String PROPERTY_QUERY = "query";
	
	/**
	 * This is the property name for grouping enabled.
	 */
	public static final String GROUPING_ENABLED = "groupingEnabled";
	
	/**
	 * This is the property name for the global where clause text.
	 */
	private static final String GLOBAL_WHERE_CLAUSE = "globalWhereClause";
	
	/**
	 * The arguments that can be added to a column in the 
	 * order by clause.
	 */
	public enum OrderByArgument {
		ASC,
		DESC,
		NONE
	}
	
	/**
	 * This graph represents the tables in the SQL statement. Each table in
	 * the statement is a vertex in the graph. Each join is an edge in the 
	 * graph coming from the left table and moving towards the right table.
	 */
	private class TableJoinGraph implements GraphModel<Container, SQLJoin> {

		public Collection<Container> getAdjacentNodes(Container node) {
			List<Container> adjacencyNodes = new ArrayList<Container>();
			if (joinMapping.get(node) != null) {
				for (SQLJoin join : joinMapping.get(node)) {
					if (join.getLeftColumn().getContainer() == node) {
						adjacencyNodes.add(join.getRightColumn().getContainer());
					}
				}
			}
			return adjacencyNodes;
		}

		public Collection<SQLJoin> getEdges() {
			List<SQLJoin> edgesList = new ArrayList<SQLJoin>();
			for (List<SQLJoin> joinList : joinMapping.values()) {
				for (SQLJoin join : joinList) {
					edgesList.add(join);
				}
			}
			return edgesList;
		}

		public Collection<SQLJoin> getInboundEdges(Container node) {
			List<SQLJoin> inboundEdges = new ArrayList<SQLJoin>();
			if (joinMapping.get(node) != null) {
				for (SQLJoin join : joinMapping.get(node)) {
					if (join.getRightColumn().getContainer() == node) {
						inboundEdges.add(join);
					}
				}
			}
			return inboundEdges;
		}

		public Collection<Container> getNodes() {
			return fromTableList;
		}

		public Collection<SQLJoin> getOutboundEdges(Container node) {
			List<SQLJoin> outboundEdges = new ArrayList<SQLJoin>();
			if (joinMapping.get(node) != null) {
				for (SQLJoin join : joinMapping.get(node)) {
					if (join.getLeftColumn().getContainer() == node) {
						outboundEdges.add(join);
					}
				}
			}
			return outboundEdges;
		}
		
	}

	/**
	 * Tracks if there are groupings added to this select statement.
	 * This will affect when columns are added to the group by collections.
	 */
	private boolean groupingEnabled = false;

	/**
	 * The columns in the SELECT statement that will be returned.
	 * These columns are stored in the order they will be returned
	 * in.
	 */
	private final List<Item> selectedColumns;
	
	/**
	 * The order by list keeps track of the order that columns were selected in.
	 */
	private final List<Item> orderByList;
	
	/**
	 * The list of tables that we are selecting from.
	 */
	private final List<Container> fromTableList;
	
	/**
	 * This maps each table to a list of SQLJoin objects.
	 * These column pairs defines a join in the select statement.
	 */
	private final Map<Container, List<SQLJoin>> joinMapping;
	
	/**
	 * This is the global where clause that is for all non-column-specific where
	 * entries.
	 */
	private String globalWhereClause;
	
	/**
	 * This is the level of the zoom in the query.
	 */
	private int zoomLevel;
	
	private final List<QueryChangeListener> changeListeners = new ArrayList<QueryChangeListener>();

    /**
     * Listens for changes to the item and fires events to its listeners.
     */
	private PropertyChangeListener itemListener = new PropertyChangeListener() {
		public void propertyChange(PropertyChangeEvent e) {
		    if (e.getPropertyName().equals(Item.SELECTED)) {
		        if ((Boolean) e.getNewValue()) {
		            selectedColumns.add((Item) e.getSource());
		        } else {
		            selectedColumns.remove((Item) e.getSource());
		        }
		    } else if (e.getPropertyName().equals(Item.ORDER_BY)) {
		        orderByList.remove(e.getSource());
		        if (!e.getNewValue().equals(OrderByArgument.NONE)) {
		            orderByList.add((Item) e.getSource());
		        }
		    }
		    
		    for (int i = changeListeners.size() - 1; i >= 0; i--) {
		        changeListeners.get(i).itemPropertyChangeEvent(e);
		    }
		}
	};

    /**
     * This change listener will re-send the change event to listeners on this
     * query. This will also keep the map of {@link Container}s to the joins on
     * them in order.
     */
	private PropertyChangeListener joinChangeListener = new PropertyChangeListener() {
		public void propertyChange(PropertyChangeEvent e) {
			if (e.getPropertyName().equals(SQLJoin.LEFT_JOIN_CHANGED)) {
				logger.debug("Got left join changed.");
				SQLJoin changedJoin = (SQLJoin) e.getSource();
				Container leftJoinContainer = changedJoin.getLeftColumn().getContainer();
				for (SQLJoin join : joinMapping.get(leftJoinContainer)) {
					if (join.getLeftColumn().getContainer() == leftJoinContainer) {
						join.setLeftColumnOuterJoin((Boolean)e.getNewValue());
					} else {
						join.setRightColumnOuterJoin((Boolean)e.getNewValue());
					}
				}
			} else if (e.getPropertyName().equals(SQLJoin.RIGHT_JOIN_CHANGED)) {
				logger.debug("Got right join changed.");
				SQLJoin changedJoin = (SQLJoin) e.getSource();
				Container rightJoinContainer = changedJoin.getRightColumn().getContainer();
				logger.debug("There are " + joinMapping.get(rightJoinContainer) + " joins on the table with the changed join.");
				for (SQLJoin join : joinMapping.get(rightJoinContainer)) {
					if (join.getLeftColumn().getContainer() == rightJoinContainer) {
						logger.debug("Changing left side");
						join.setLeftColumnOuterJoin((Boolean)e.getNewValue());
					} else {
						logger.debug("Changing right side");
						join.setRightColumnOuterJoin((Boolean)e.getNewValue());
					}
				}
			}
			for (int i = changeListeners.size() - 1; i >= 0; i--) {
			    changeListeners.get(i).joinPropertyChangeEvent(e);
			}
		}
	};
	
	private final ContainerChildListener tableChildListener = new ContainerChildListener() {
		public void containerChildRemoved(ContainerChildEvent e) {
			removeItem(e.getChild());
		}
		public void containerChildAdded(ContainerChildEvent e) {
			addItem(e.getChild());
		}
	}; 
	
	/**
	 * This container holds the items that are considered constants in the SQL statement.
	 * This could include functions or other elements that don't belong in a table.
	 */
	private Container constantsContainer;
	
	/**
	 * If true the query cache will be in an editing state. When in this
	 * state the query should not be executed. When the query can be executed
	 * again the listeners should have the {@link QueryChangeListener#canExecuteQuery()}
	 * method called on them to let all the classes know they can execute the
	 * query again.
	 */
	private boolean canExecuteQuery = true;

	/**
	 * This database instance is obtained from the session when the 
	 * data source is called.
	 */
	private SQLDatabase database;
	
	/**
	 * This is the text of the query if the user edited the text manually. This means
	 * that the parts of the query cache will not represent the new query text the user
	 * created. If this is null then the user did not change the query manually.
	 */
	private String userModifiedQuery = null;
	
	private SQLDatabaseMapping dbMapping;
	
	private final UUID uuid;
	
	private String name;
	
    /**
     * This is the streaming row limit for the query. No more than this many
     * rows will be shown in the streaming result set and if this limit is
     * reached and new rows are added then the oldest rows in the result set
     * will be removed.
     */
    private int streamingRowLimit = 1000;
    
    /**
     * This is the row limit of a standard query. 
     */
    private int rowLimit = 1000;
    
    /**
     * Tracks if the data source should be used as a streaming query or as a regular
     * query. Streaming queries are populated on their own thread.
     */
    private boolean streaming = false;

    /**
     * A property change of this type is fired if the user defined
     * text of the query is modified. Property changes to the objects
     * maintained and monitored by this query will not contain this
     * type.
     */
    public static final String USER_MODIFIED_QUERY = "userModifiedQuery";

    /**
     * A property name that is thrown when the Table is removed.
     */
    public static final String PROPERTY_TABLE_REMOVED = "PROPERTY_TABLE_REMOVED";

	public Query(SQLDatabaseMapping dbMapping) {
		this((String)null, dbMapping);
	}
	
	/**
	 * The uuid defines the unique id of this query cache. If null
	 * is passed in a new UUID will be generated.
	 */
	public Query(String uuid, SQLDatabaseMapping dbMapping) {
		if (uuid == null) {
		    this.uuid = UUID.randomUUID();
		} else {
		    this.uuid = UUID.fromString(uuid);
		}
        this.dbMapping = dbMapping;
		orderByList = new ArrayList<Item>();
		selectedColumns = new ArrayList<Item>();
		fromTableList = new ArrayList<Container>();
		joinMapping = new HashMap<Container, List<SQLJoin>>();
		
		constantsContainer = new ItemContainer("Constants");
		StringItem currentTime = new StringItem("current_time");
		constantsContainer.addItem(currentTime);
		addItem(currentTime);
		StringItem currentDate = new StringItem("current_date");
		constantsContainer.addItem(currentDate);
		addItem(currentDate);
		StringItem user = new StringItem("user");
		constantsContainer.addItem(user);
		addItem(user);
	}
	
	/**
	 * A copy constructor for the query cache. This will not
	 * hook up listeners.
	 */
	public Query(Query copy, boolean connectListeners) {
	    uuid = UUID.randomUUID();
		selectedColumns = new ArrayList<Item>();
		fromTableList = new ArrayList<Container>();
		joinMapping = new HashMap<Container, List<SQLJoin>>();
		orderByList = new ArrayList<Item>();
		
		dbMapping = copy.getDBMapping();
		setName(copy.getName());

		Map<Container, Container> oldToNewContainers = new HashMap<Container, Container>();
		for (Container table : copy.getFromTableList()) {
		    final Container tableCopy = table.createCopy();
		    oldToNewContainers.put(table, tableCopy);
            fromTableList.add(tableCopy);
		}
		
		constantsContainer = copy.getConstantsContainer().createCopy();
		oldToNewContainers.put(copy.getConstantsContainer(), constantsContainer);
		
		for (Item column : copy.getSelectedColumns()) {
		    Container table = oldToNewContainers.get(column.getParent());
		    Item item = table.getItem(column.getItem());
		    selectedColumns.add(item);
		}

		Set<SQLJoin> joinSet = new HashSet<SQLJoin>();
		for (Map.Entry<Container, List<SQLJoin>> entry : copy.getJoinMapping().entrySet()) {
		    joinSet.addAll(entry.getValue());
		}
		
		for (SQLJoin oldJoin : joinSet) {
		    Container newLeftContainer = oldToNewContainers.get(oldJoin.getLeftColumn().getContainer());
		    Item newLeftItem = newLeftContainer.getItem(oldJoin.getLeftColumn().getItem());
		    Container newRightContainer = oldToNewContainers.get(oldJoin.getRightColumn().getContainer());
		    Item newRightItem = newRightContainer.getItem(oldJoin.getRightColumn().getItem());
		    SQLJoin newJoin = new SQLJoin(newLeftItem, newRightItem);
		    newJoin.setComparator(oldJoin.getComparator());
		    newJoin.setLeftColumnOuterJoin(oldJoin.isLeftColumnOuterJoin());
		    newJoin.setRightColumnOuterJoin(oldJoin.isRightColumnOuterJoin());
		    newJoin.setName(oldJoin.getName());
		    
		    List<SQLJoin> newJoinList = joinMapping.get(newLeftContainer);
		    if (newJoinList == null) {
		        newJoinList = new ArrayList<SQLJoin>();
		        joinMapping.put(newLeftContainer, newJoinList);
		    }
		    newJoinList.add(newJoin);
		    
		    newJoinList = joinMapping.get(newRightContainer);
            if (newJoinList == null) {
                newJoinList = new ArrayList<SQLJoin>();
                joinMapping.put(newRightContainer, newJoinList);
            }
            newJoinList.add(newJoin);
		}

		for (Item column : copy.getOrderByList()) {
		    Container table = oldToNewContainers.get(column.getParent());
		    Item item = table.getItem(column.getItem());
		    orderByList.add(item);
		}
		globalWhereClause = copy.getGlobalWhereClause();
		groupingEnabled = copy.isGroupingEnabled();
		streaming = copy.isStreaming();

		database = copy.getDatabase();
		userModifiedQuery = copy.getUserModifiedQuery();
		
		if (connectListeners) {
		    for (Container table : fromTableList) {
		        table.addChildListener(tableChildListener);
		        for (Item column : table.getItems()) {
		            column.addPropertyChangeListener(itemListener);
		        }
		    }
		    //XXX does the constants container not need a child listener?
		    for (Item column : constantsContainer.getItems()) {
		        column.addPropertyChangeListener(itemListener);
		    }
		    for (SQLJoin join : joinSet) {
		        join.addJoinChangeListener(joinChangeListener);
		    }
		}
	}
	
	public void setDBMapping(SQLDatabaseMapping dbMapping) {
	    this.dbMapping = dbMapping;
	}
	
	public SQLDatabase getDatabase() {
        return database;
    }
	
	public void setGroupingEnabled(boolean enabled) {
		logger.debug("Setting grouping enabled to " + enabled);
		if (!groupingEnabled && enabled) {
			startCompoundEdit();
			for (Item item : getSelectedColumns()) {
				if (item instanceof StringItem) {
					item.setGroupBy(SQLGroupFunction.COUNT);
				}
			}
			endCompoundEdit();
		}
		for (int i = changeListeners.size() - 1; i >= 0; i--) {
            changeListeners.get(i).propertyChangeEvent(new PropertyChangeEvent(this, GROUPING_ENABLED, groupingEnabled, enabled));
		}
		groupingEnabled = enabled;
	}
	
	/**
	 * Removes the column from the selected columns list and all other
	 * related lists.
	 */
	private void removeColumnSelection(Item column) {
		selectedColumns.remove(column);
		orderByList.remove(column);
	}
	
	/**
	 * Generates the query based on the cache.
	 */
	public String generateQuery() {
	    JDBCDataSource dataSource = null;
	    if (database != null) {
            dataSource = database.getDataSource();
	    }
        logger.debug("Data source is " + dataSource + " while generating the query.");
		ConstantConverter converter = ConstantConverter.getConverter(dataSource);
		if (userModifiedQuery != null) {
			return userModifiedQuery;
		}
		if (selectedColumns.size() ==  0) {
			return "";
		}
		StringBuffer query = new StringBuffer();
		query.append("SELECT");
		boolean isFirstSelect = true;
		for (Item col : selectedColumns) {
			if (isFirstSelect) {
				query.append(" ");
				isFirstSelect = false;
			} else {
				query.append(", ");
			}
			if (groupingEnabled && !col.getGroupBy().equals(SQLGroupFunction.GROUP_BY)) {
				if(col instanceof StringCountItem) {
					query.append(col.getName());
				} else {
					query.append(col.getGroupBy() + "(");
				}
			}
			String alias = col.getContainer().getAlias();
			if (alias != null && alias.length() > 0) {
				query.append(alias + ".");
			} else if (fromTableList.contains(col.getContainer())) {
				query.append(col.getContainer().getName() + ".");
			}
			if(!(col instanceof StringCountItem)) {
				query.append(converter.getName(col));			
			}
			if (groupingEnabled && !col.getGroupBy().equals(SQLGroupFunction.GROUP_BY) && !(col instanceof StringCountItem)) {
				query.append(")");
			}
			if (col.getAlias() != null && col.getAlias().trim().length() > 0) {
				query.append(" AS " + col.getAlias());
			}
		}
		if (!fromTableList.isEmpty()) {
			query.append(" \nFROM");
		}
		boolean isFirstFrom = true;
		
		DepthFirstSearch<Container, SQLJoin> dfs = new DepthFirstSearch<Container, SQLJoin>();
		dfs.performSearch(new TableJoinGraph());
		Container previousTable = null;
		for (Container table : dfs.getFinishOrder()) {
			String qualifiedName;
			if (table.getContainedObject() instanceof SQLTable) {
				qualifiedName = ((SQLTable)table.getContainedObject()).toQualifiedName();
			} else {
				qualifiedName = table.getName();
			}
			String alias = table.getAlias();
			if (alias == null || alias.length() <= 0) {
				alias = table.getName();
			}
			if (isFirstFrom) {
				query.append(" " + qualifiedName + " " + alias);
				isFirstFrom = false;
			} else {
				boolean joinFound = false;
				if (previousTable != null && joinMapping.get(table) != null) {
					for (SQLJoin join : joinMapping.get(table)) {
						if (join.getLeftColumn().getContainer() == previousTable) {
							joinFound = true;
							if (join.isLeftColumnOuterJoin() && join.isRightColumnOuterJoin()) {
								query.append(" \nFULL OUTER JOIN ");
							} else if (join.isLeftColumnOuterJoin() && !join.isRightColumnOuterJoin()) {
								query.append(" \nLEFT OUTER JOIN ");
							} else if (!join.isLeftColumnOuterJoin() && join.isRightColumnOuterJoin()) {
								query.append(" \nRIGHT OUTER JOIN ");
							} else {
								query.append(" \nINNER JOIN ");
							}
							break;
						}
					}
				}
				if (!joinFound) {
					query.append(" \nINNER JOIN ");
				}
				query.append(qualifiedName + " " + alias + " \n  ON ");
				if (joinMapping.get(table) == null || joinMapping.get(table).isEmpty()) {
					query.append("0 = 0");
				} else {
					boolean isFirstJoin = true;
					for (SQLJoin join : joinMapping.get(table)) {
						Item otherColumn;
						if (join.getLeftColumn().getContainer() == table) {
							otherColumn = join.getRightColumn();
						} else {
							otherColumn = join.getLeftColumn();
						}
						for (int i = 0; i < dfs.getFinishOrder().indexOf(table); i++) {
							if (otherColumn.getContainer() == dfs.getFinishOrder().get(i)) {
								if (isFirstJoin) {
									isFirstJoin = false;
								} else {
									query.append(" \n    AND ");
								}
								String leftAlias = join.getLeftColumn().getContainer().getAlias();
								if (leftAlias == null || leftAlias.length() <= 0) {
									leftAlias = join.getLeftColumn().getContainer().getName();
								}
								String rightAlias = join.getRightColumn().getContainer().getAlias();
								if (rightAlias == null || rightAlias.length() <= 0) {
									rightAlias = join.getRightColumn().getContainer().getName();
								}
								query.append(leftAlias + "." + join.getLeftColumn().getName() + 
										" " + join.getComparator() + " " + 
										rightAlias + "." + join.getRightColumn().getName());
							}
						}
					}
					if (isFirstJoin) {
						query.append("0 = 0");
					}
				}
			}
			previousTable = table;
		}
		query.append(" ");
		boolean isFirstWhere = true;
		Map<Item, String> whereMapping = new HashMap<Item, String>();
		for (Item item : constantsContainer.getItems()) {
			if (item.getWhere() != null && item.getWhere().trim().length() > 0) {
				whereMapping.put(item, item.getWhere());
			}
		}
		for (Container container : fromTableList) {
			for (Item item : container.getItems()) {
				if (item.getWhere() != null && item.getWhere().trim().length() > 0) {
					whereMapping.put(item, item.getWhere());
				}
			}
		}
		for (Map.Entry<Item, String> entry : whereMapping.entrySet()) {
			if (entry.getValue().length() > 0) {
				if (isFirstWhere) {
					query.append(" \nWHERE ");
					isFirstWhere = false;
				} else {
					query.append(" AND ");
				}
				String alias = entry.getKey().getContainer().getAlias();
				if (alias != null && alias.length() > 0) {
					query.append(alias + ".");
				} else if (fromTableList.contains(entry.getKey().getContainer())) {
					query.append(entry.getKey().getContainer().getName() + ".");
				}
				query.append(entry.getKey().getName() + " " + entry.getValue());
			}
		}
		if ((globalWhereClause != null && globalWhereClause.length() > 0)) {
			if (!isFirstWhere) {
				query.append(" AND"); 
			} else {
				query.append(" \nWHERE ");
			}
			query.append(" " + globalWhereClause);
		}
		if (groupingEnabled) {
		    boolean isFirstGroupBy = true;
		    for (Item col : selectedColumns) {
		        if (col.getGroupBy().equals(SQLGroupFunction.GROUP_BY)) {
		            if (isFirstGroupBy) {
		                query.append("\nGROUP BY ");
		                isFirstGroupBy = false;
		            } else {
		                query.append(", ");
		            }
		            String alias = col.getContainer().getAlias();
		            if (alias != null && alias.length() > 0) {
		                query.append(alias + ".");
		            } else if (fromTableList.contains(col.getContainer())) {
		                query.append(col.getContainer().getName() + ".");
		            }
		            query.append(col.getName());
		        }
		    }
		    query.append(" ");
		    boolean isFirstHaving = true;
		    for (Item column : selectedColumns) {
		        if (column.getHaving() != null && column.getHaving().trim().length() > 0) {
		            if (isFirstHaving) {
		                query.append("\nHAVING ");
		                isFirstHaving = false;
		            } else {
		                query.append(", ");
		            }
		            if (column.getGroupBy() != null) {
		                query.append(column.getGroupBy() + "(");
		            }
		            String alias = column.getContainer().getAlias();
		            if (alias != null && alias.length() > 0) {
		                query.append(alias + ".");
		            } else if (fromTableList.contains(column.getContainer())) {
		                query.append(column.getContainer().getName() + ".");
		            }
		            query.append(column.getName());
		            if (column.getGroupBy() != null) {
		                query.append(")");
		            }
		            query.append(" ");
		            query.append(column.getHaving());
		        }
		    }
		    query.append(" ");
		}
		
		if (!orderByList.isEmpty()) {
			boolean isFirstOrder = true;
			for (Item col : orderByList) {
				if (col instanceof StringItem) {
					continue;
				}
				if (isFirstOrder) {
					query.append("\nORDER BY ");
					isFirstOrder = false;
				} else {
					query.append(", ");
				}
				if (groupingEnabled && !col.getGroupBy().equals(SQLGroupFunction.GROUP_BY)) {
					query.append(col.getGroupBy() + "(");
				}
				String alias = col.getContainer().getAlias();
				if (alias != null && alias.length() > 0) {
					query.append(alias + ".");
				} else if (fromTableList.contains(col.getContainer())) {
					query.append(col.getContainer().getName() + ".");
				}
				query.append(col.getName());
				if (groupingEnabled && !col.getGroupBy().equals(SQLGroupFunction.GROUP_BY)) {
					query.append(")");
				}
				query.append(" ");
				if (!col.getOrderBy().equals(OrderByArgument.NONE)) {
					query.append(col.getOrderBy().toString() + " ");
				}
			}
		}
		logger.debug(" Query is : " + query.toString());
		return query.toString();
	}

	public List<Item> getSelectedColumns() {
		return Collections.unmodifiableList(selectedColumns);
	}

	public List<Item> getOrderByList() {
		return Collections.unmodifiableList(orderByList);
	}
	
	/**
	 * This will move a column that is being sorted to the end of the
	 * list of columns that are being sorted.
	 */
	public void moveSortedItemToEnd(Item item) {
	    orderByList.remove(item);
	    orderByList.add(item);
	}
	
	public void removeTable(Container table) {
		fromTableList.remove(table);
		table.removeChildListener(tableChildListener);
		for (Item col : table.getItems()) {
			removeItem(col);
		}
		for (int i = changeListeners.size() - 1; i >= 0; i--) {
		    changeListeners.get(i).containerRemoved(new QueryChangeEvent(this, table));
		}
	}

	public void addTable(Container container) {
		fromTableList.add(container);
		container.addChildListener(tableChildListener);
		for (Item col : container.getItems()) {
			addItem(col);
		}
		for (int i = changeListeners.size() - 1; i >= 0; i--) {
            changeListeners.get(i).containerAdded(new QueryChangeEvent(this, container));
        }
	}
	
	/**
	 * This setter will fire a property change event.
	 */
	public void setGlobalWhereClause(String whereClause) {
		String oldWhere = globalWhereClause;
		globalWhereClause = whereClause;
		for (int i = changeListeners.size() - 1; i >= 0; i--) {
		    changeListeners.get(i).propertyChangeEvent(new PropertyChangeEvent(this, GLOBAL_WHERE_CLAUSE, oldWhere, whereClause));
		}
	}
	
	public void removeJoin(SQLJoin joinLine) {
		joinLine.removeJoinChangeListener(joinChangeListener);
		Item leftColumn = joinLine.getLeftColumn();
		Item rightColumn = joinLine.getRightColumn();

		List<SQLJoin> leftJoinList = joinMapping.get(leftColumn.getContainer());
		for (SQLJoin join : leftJoinList) {
			if (leftColumn == join.getLeftColumn() && rightColumn == join.getRightColumn()) {
				leftJoinList.remove(join);
				break;
			}
		}

		List<SQLJoin> rightJoinList = joinMapping.get(rightColumn.getContainer());
		for (SQLJoin join : rightJoinList) {
			if (leftColumn == join.getLeftColumn() && rightColumn == join.getRightColumn()) {
				rightJoinList.remove(join);
				break;
			}
		}
		for (int i = changeListeners.size() - 1; i >= 0; i--) {
		    changeListeners.get(i).joinRemoved(new QueryChangeEvent(this, joinLine));
		}
	}

	public void addJoin(SQLJoin join) {
		join.addJoinChangeListener(joinChangeListener);
		Item leftColumn = join.getLeftColumn();
		Item rightColumn = join.getRightColumn();
		Container leftContainer = leftColumn.getContainer();
		Container rightContainer = rightColumn.getContainer();
		if (joinMapping.get(leftContainer) == null) {
			List<SQLJoin> joinList = new ArrayList<SQLJoin>();
			joinList.add(join);
			joinMapping.put(leftContainer, joinList);
		} else {
			if (joinMapping.get(leftContainer).size() > 0) {
				SQLJoin prevJoin = joinMapping.get(leftContainer).get(0);
				if (prevJoin.getLeftColumn().getContainer() == leftContainer) {
					join.setLeftColumnOuterJoin(prevJoin.isLeftColumnOuterJoin());
				} else if (prevJoin.getRightColumn().getContainer() == leftContainer) {
					join.setLeftColumnOuterJoin(prevJoin.isRightColumnOuterJoin());
				}
			}
				
			joinMapping.get(leftContainer).add(join);
		}

		if (joinMapping.get(rightContainer) == null) {
			List<SQLJoin> joinList = new ArrayList<SQLJoin>();
			joinList.add(join);
			joinMapping.put(rightContainer, joinList);
		} else {
			if (joinMapping.get(rightContainer).size() > 0) {
				SQLJoin prevJoin = joinMapping.get(rightContainer).get(0);
				if (prevJoin.getLeftColumn().getContainer() == rightContainer) {
					join.setRightColumnOuterJoin(prevJoin.isLeftColumnOuterJoin());
				} else if (prevJoin.getRightColumn().getContainer() == rightContainer) {
					join.setRightColumnOuterJoin(prevJoin.isRightColumnOuterJoin());
				} else {
					throw new IllegalStateException("A table contains a join that is not connected to any of its columns in the table.");
				}
			}
			joinMapping.get(rightContainer).add(join);
		}
		for (int i = changeListeners.size() - 1; i >= 0; i--) {
		    changeListeners.get(i).joinAdded(new QueryChangeEvent(this, join));
		}
	}
	
	/**
	 * This removes the item from all lists it could be
	 * contained in as well as disconnect its listeners.
	 */
	public void removeItem(Item col) {
		logger.debug("Item name is " + col.getName());
		col.removePropertyChangeListener(itemListener);
		removeColumnSelection(col);
		for (int i = changeListeners.size() - 1; i >= 0; i--) {
		    changeListeners.get(i).itemRemoved(new QueryChangeEvent(this, col));
		}
	}
	
	/**
	 * This adds the appropriate listeners to the new Item.
	 */
	public void addItem(Item col) {
		col.addPropertyChangeListener(itemListener);
		for (int i = changeListeners.size() - 1; i >= 0; i--) {
		    changeListeners.get(i).itemAdded(new QueryChangeEvent(this, col));
		}
	}
	
	public void moveItem(Item movedColumn, int toIndex) {
		selectedColumns.remove(movedColumn);
		selectedColumns.add(toIndex, movedColumn);
		for (int i = changeListeners.size() - 1; i >= 0; i--) {
		    changeListeners.get(i).itemOrderChanged(new QueryChangeEvent(this, movedColumn));
		}
	}
	
	public void startCompoundEdit() {
		setCanExecuteQuery(false);
	}
	
	public void endCompoundEdit() {
		setCanExecuteQuery(true);
	}

	public boolean isGroupingEnabled() {
		return groupingEnabled;
	}

	public List<Container> getFromTableList() {
		return Collections.unmodifiableList(fromTableList);
	}

	protected Map<Container, List<SQLJoin>> getJoinMapping() {
		return Collections.unmodifiableMap(joinMapping);
	}
	
	/**
	 * This returns the joins between tables. Each join will be
	 * contained only once.
	 */
	public Collection<SQLJoin> getJoins() {
		Set<SQLJoin> joinSet = new HashSet<SQLJoin>();
		for (List<SQLJoin> joins : joinMapping.values()) {
			for (SQLJoin join : joins) {
				joinSet.add(join);
			}
		}
		return joinSet;
	}

	public String getGlobalWhereClause() {
		return globalWhereClause;
	}

	public Container getConstantsContainer() {
		return constantsContainer;
	}
	
	public void setDataSource(JDBCDataSource dataSource) {
	    this.database = dbMapping.getDatabase(dataSource);
	    if (dataSource != null) {
            setStreaming(dataSource.getParentType().getSupportsStreamQueries());
        }
	}
	
	/**
	 * If this is set then only this query string will be returned by the generateQuery method
	 * and the query cache will not accurately represent the query.
	 */
	public void defineUserModifiedQuery(String query) {
		String generatedQuery = generateQuery();
		logger.debug("Generated query is " + generatedQuery + " and given query is " + query);
		if (generatedQuery.equals(query)) {
			return;
		}
		for (int i = changeListeners.size() - 1; i >= 0; i--) {
            changeListeners.get(i).propertyChangeEvent(new PropertyChangeEvent(this, USER_MODIFIED_QUERY, userModifiedQuery, query));
        }
		userModifiedQuery = query;
	}
	
	/**
	 * Returns true if the user manually edited the text of the query. Returns false otherwise.
	 */
	public boolean isScriptModified() {
		return userModifiedQuery != null;
	}
	
	/**
	 * Resets the manual modifications the user did to the text of the query so the textual
	 * query is the same as the query cache.
	 */
	public void removeUserModifications() {
		logger.debug("Removing user modified query.");
		userModifiedQuery = null;
	}

	/**
	 * Creates a new constants container for this QueryCache. This should
	 * only be used in loading.
	 */
	public Container newConstantsContainer(String uuid) {
		constantsContainer = new ItemContainer("Constants", uuid);
		return constantsContainer;
	}

	public void setZoomLevel(int zoomLevel) {
		this.zoomLevel = zoomLevel;
	}

	public int getZoomLevel() {
		return zoomLevel;
	}
	
	/**
	 * Used for constructing copies of the query cache.
	 */
	protected String getUserModifiedQuery() {
		return userModifiedQuery;
	}
	
	/**
	 * Used in the copy constructor to set the database mapping.
	 */
	public SQLDatabaseMapping getDBMapping() {
		return dbMapping;
	}
	
	@Override
	public String toString() {
		return getName();
	}

	public void setDatabase(SQLDatabase db) {
		database = db;
	}

    public UUID getUUID() {
        return uuid;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public void setRowLimit(int rowLimit) {
        int oldLimit = this.rowLimit;
        this.rowLimit = rowLimit;
        for (int i = changeListeners.size() - 1; i >= 0; i--) {
            changeListeners.get(i).propertyChangeEvent(new PropertyChangeEvent(this, ROW_LIMIT, oldLimit, rowLimit));
        }
    }

    public int getRowLimit() {
        return rowLimit;
    }
    
    public void setStreamingRowLimit(int streamingRowLimit) {
        this.streamingRowLimit = streamingRowLimit;
    }

    public int getStreamingRowLimit() {
        return streamingRowLimit;
    }

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
    }

    public boolean isStreaming() {
        return streaming;
    }

    public void setCanExecuteQuery(boolean canExecuteQuery) {
        this.canExecuteQuery = canExecuteQuery;
        if (canExecuteQuery) {
            for (int i = changeListeners.size() - 1; i >= 0; i--) {
                changeListeners.get(i).canExecuteQuery();
            }
        }
    }

    public boolean getCanExecuteQuery() {
        return canExecuteQuery;
    }
    
    public void addQueryChangeListener(QueryChangeListener l) {
        changeListeners.add(l);
    }
    
    public void removeQueryChangeListener(QueryChangeListener l) {
        changeListeners.remove(l);
    }
}