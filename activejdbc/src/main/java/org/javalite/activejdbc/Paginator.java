/*
Copyright 2009-2014 Igor Polevoy

Licensed under the Apache License, Version 2.0 (the "License"); 
you may not use this file except in compliance with the License. 
You may obtain a copy of the License at 

http://www.apache.org/licenses/LICENSE-2.0 

Unless required by applicable law or agreed to in writing, software 
distributed under the License is distributed on an "AS IS" BASIS, 
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
See the License for the specific language governing permissions and 
limitations under the License. 
*/


package org.javalite.activejdbc;

import org.javalite.activejdbc.cache.QueryCache;
import org.javalite.common.Convert;

import java.io.Serializable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class supports pagination of result sets in ActiveJDBC. This is useful for paging through tables. If the
 * Model subclass is annotated with @{@link org.javalite.activejdbc.annotations.Cached}, then this class will
 * cache the total count of records returned by {@link #getCount()}, as LazyList will cache the result sets.
 * This class is thread safe and the same instance could be used across multiple web requests and even
 * across multiple users/sessions. It is lightweight class, you can generate an instance each time you need one,
 * or you can cache an instance in a session or even servlet context.
 *
 * @author Igor Polevoy
 */
public class Paginator<T extends Model> implements Serializable {
    static final Pattern FROM_PATTERN = Pattern.compile("FROM", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private final int pageSize;
    private final String query;
    private String orderBys;
    private final Object[] params;
    private final MetaModel metaModel;
    private int currentPage;
    private final boolean fullQuery;
    private final String countQuery;


    /**
     * Paginator is created with parameters to jump to chunks of result sets (pages). This class is useful "paging"
     * through result on a user interface (web page).
     *
     * <h4>Examples of a sub-query:</h4>
     * <ul>
     *     <li><code>"last_name like '%John%'"</code> - this is a sub-query, and the rest of the information will be filled out
     * by this class</li>
     *     <li> "*" - will search for all records, no filtering</li>
     * </ul>
     * Sub-query is used in simple cases, when filtering is done against one table.
     *
     * <h4>Full query example</h4>
     * <ul>
     *     <li>"select * from people where last_name like '%John%'"</li>
     * </ul>
     * Full query is used in cases when select covers many tables. In this case, the selected columns need to include
     * attributes of the model class.
     *
     * @param modelClass model class mapped to a table.
     * @param pageSize   number of items per page.
     * @param params a set of parameters if a query is parametrized (has question marks '?').
     * @param query      this is a query that will be applied every time a new page is requested; this
     * query should not contain limit, offset or order by clauses of any kind, Paginator will do this automatically.
     * This parameter can have two forms, a sub-query or a full query.
     *
     *
     */
    public Paginator(Class<? extends T> modelClass, int pageSize, String query, Object... params) {

        try{
            Class.forName(modelClass.getName());
        }catch(ClassNotFoundException e){
            throw new InitException(e);
        }

        this.pageSize = pageSize;
        this.query = query;
        this.params = params;
        String tableName = Registry.instance().getTableName(modelClass);
        this.metaModel = Registry.instance().getMetaModel(tableName);

        this.fullQuery = DB.SELECT_PATTERN.matcher(query).find();
        if (fullQuery) {
            Matcher m = FROM_PATTERN.matcher(query);
            if (!m.find()) {
                throw new IllegalArgumentException("SELECT query without FROM");
            }
            this.countQuery = "SELECT COUNT(*) " + query.substring(m.start());
        } else if (query.equals("*")) {
            if (params.length == 0) {
                this.countQuery = metaModel.getDialect().selectCount(tableName);
            } else{
                throw new IllegalArgumentException("cannot provide parameters with query: '*'");
            }
        } else {
            this.countQuery = metaModel.getDialect().selectCount(tableName, query);
        }
    }

    /**
     * Use to set order by(s). Example: <code>"category, created_at desc"</code>
     *
     * @param orderBys a comma-separated list of field names followed by either "desc" or "asc"
     * @return instance to self.
     */
    public Paginator orderBy(String orderBys) {
        this.orderBys = orderBys;
        return this;
    }

    /**
     * This method will return a list of records for a specific page.
     *
     * @param pageNumber page number to return. This is indexed at 1, not 0. Any value below 1 is illegal and will
     * be rejected.
     * @return list of records that match a query make up a "page".
     */
    public LazyList<T> getPage(int pageNumber) {

        if (pageNumber < 1) throw new IllegalArgumentException("minimum page index == 1");

        try {
            LazyList<T> list = find(query, params).offset((pageNumber - 1) * pageSize).limit(pageSize);
            if (orderBys != null) {
                list.orderBy(orderBys);
            }
            currentPage = pageNumber;
            return list;
        } catch (Exception mustNeverHappen) {
            throw new InternalException(mustNeverHappen);
        }
    }

    /**
     * Returns index of current page, or 0 if this instance has not produced a page yet.
     *
     * @return index of current page, or 0 if this instance has not produced a page yet.
     */
    public int getCurrentPage(){
        return currentPage;
    }

    /**
     * Synonym for {@link #hasPrevious()}.
     *
     * @return true if a previous page is available. 
     */
    public boolean getPrevious(){
        return hasPrevious();
    }
    public boolean hasPrevious(){
        return currentPage > 1 && currentPage <= pageCount();
    }

    /**
     * Synonym for {@link #hasNext()}.
     * 
     * @return true if a next page is available. 
     */
    public boolean getNext(){
        return hasNext();
    }

    public boolean hasNext(){
        return currentPage < pageCount();
    }
    
    public long pageCount() {
        try {
            long results = getCount();
            long fullPages = results / pageSize;
            return results % pageSize == 0 ? fullPages : fullPages + 1;
        } catch (Exception mustNeverHappen) {
            throw new InternalException(mustNeverHappen);
        }
    }

    private LazyList<T> find(String query, Object... params) {
        if (query.equals("*")) {
            if (params.length == 0) {
                return findAll();
            } else{
                throw new IllegalArgumentException("cannot provide parameters with query: '*'");
            }
        }
        return fullQuery ? new LazyList<T>(true, metaModel, this.query, params) 
                         : new LazyList<T>(query, metaModel, params);
    }

    private LazyList<T> findAll() {
        return new LazyList<T>(null, metaModel);
    }

    /**
     * Returns total count of records based on provided criteria.
     *
     * @return total count of records based on provided criteria
     */
    public Long getCount() {
        Long result = null;
        if (metaModel.cached()) {
            result = (Long) QueryCache.instance().getItem(metaModel.getTableName(), countQuery, params);
            if (result == null) {
                result = doCount();
                QueryCache.instance().addItem(metaModel.getTableName(), countQuery, params, result);
            }
        } else {
            result = doCount();
        }
        return result;
    }

    private Long doCount(){
        return Convert.toLong(new DB(metaModel.getDbName()).firstCell(countQuery, params));
    }
}
