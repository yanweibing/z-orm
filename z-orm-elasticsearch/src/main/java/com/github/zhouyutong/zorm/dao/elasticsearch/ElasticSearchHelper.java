package com.github.zhouyutong.zorm.dao.elasticsearch;

import com.github.zhouyutong.zorm.annotation.PK;
import com.github.zhouyutong.zorm.constant.MixedConstant;
import com.github.zhouyutong.zorm.dao.DaoHelper;
import com.github.zhouyutong.zorm.dao.elasticsearch.annotation.Document;
import com.github.zhouyutong.zorm.entity.IdEntity;
import com.github.zhouyutong.zorm.exception.DaoException;
import com.github.zhouyutong.zorm.query.Criteria;
import com.github.zhouyutong.zorm.query.CriteriaOperators;
import com.github.zhouyutong.zorm.query.GroupBy;
import com.github.zhouyutong.zorm.utils.BeanUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.github.zhouyutong.zorm.query.Update;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.index.query.QueryBuilders.*;


/**
 * @Author zhouyutong
 * @Date 2017/5/11
 */
public final class ElasticSearchHelper {
    /**
     * es 5.4.3版本默认的size是10
     */
    public static final int DEFAULT_SIZE = 10;
    /**
     * 批量更新最大数量
     */
    public static final int MAX_UPDATE_SIZE = 1000;
    public static final char COMMON_WILDCARD = '%';
    public static final char ES_WILDCARD = '*';
    public static final String ES_VERSION_FIELD_NAME = "esVersion";

    private ElasticSearchHelper() {
    }

    /**
     * 根据criteria创建QueryBuilder
     *
     * @param criteria -
     * @return
     */
    public static QueryBuilder criteria2QueryBuilder(Criteria criteria) {
        if (criteria == null) {
            return null;
        }
        BoolQueryBuilder boolQueryBuilder = boolQuery();
        List<Criteria> criterias = criteria.getCriteriaChain();
        for (Criteria c : criterias) {
            String field = c.getKey();
            String operator = c.getOperator();
            Object value = c.getValue();

            if (CriteriaOperators.EQ.match(operator)) {
                boolQueryBuilder.must(termQuery(field, value));
            } else if (CriteriaOperators.GTE.match(operator)) {
                boolQueryBuilder.must(rangeQuery(field).gte(value));
            } else if (CriteriaOperators.GT.match(operator)) {
                boolQueryBuilder.must(rangeQuery(field).gt(value));
            } else if (CriteriaOperators.LTE.match(operator)) {
                boolQueryBuilder.must(rangeQuery(field).lte(value));
            } else if (CriteriaOperators.LT.match(operator)) {
                boolQueryBuilder.must(rangeQuery(field).lt(value));
            } else if (CriteriaOperators.NE.match(operator)) {
                boolQueryBuilder.mustNot(termQuery(field, value));
            } else if (CriteriaOperators.IN.match(operator)) {
                Collection collection = (Collection) value;
                boolQueryBuilder.must(termsQuery(field, collection));
            } else if (CriteriaOperators.NIN.match(operator)) {
                Collection collection = (Collection) value;
                boolQueryBuilder.mustNot(termsQuery(field, collection));
            } else if (CriteriaOperators.LIKE.match(operator)) {         //ES LIKE等同于not analyzed 的包含操作
                String v = (String) value;
                char firstChar = v.charAt(0);
                char lastChar = v.charAt(v.length() - 1);
                if (firstChar != COMMON_WILDCARD && lastChar == COMMON_WILDCARD) {
                    boolQueryBuilder.must(prefixQuery(field, v));
                } else {
                    boolQueryBuilder.must(wildcardQuery(field, v.replaceAll(Character.toString(COMMON_WILDCARD), Character.toString(ES_WILDCARD))));
                }
            } else if (CriteriaOperators.MATCH.match(operator)) {
                boolQueryBuilder.must(matchQuery(field, value));
            } else if (CriteriaOperators.MATCH_PHRASE.match(operator)) {
                boolQueryBuilder.must(matchPhraseQuery(field, value));
            }

        }
        return boolQueryBuilder;
    }

    static String[] includeFileds(List<String> fields) {
        if (CollectionUtils.isEmpty(fields)) {
            return MixedConstant.EMPTY_STRING_ARRAY;
        }
        return fields.toArray(new String[fields.size()]);
    }

    static <T> List<T> getEntityList(SearchResponse searchResponse, Class<T> entityClass, boolean hasEsVersionFiled) {
        SearchHits searchHits = searchResponse.getHits();
        if (searchHits.getTotalHits() == MixedConstant.LONG_0) {
            return Collections.emptyList();
        }

        List<T> entityList = Lists.newArrayList();
        for (SearchHit searchHit : searchHits.getHits()) {
            String source = setEsVersion(searchHit, hasEsVersionFiled);
            entityList.add(FastJson.jsonStr2Object(source, entityClass));
        }
        return entityList;
    }

    /**
     * 聚合的处理
     *
     * @param groupByList -
     * @return
     */
    static TermsAggregationBuilder groupBy2AggregationBuilder(List<GroupBy> groupByList) {
        if (groupByList.size() > 1) { //暂时只支持一个
            throw new DaoException("ElasticSearchBaseDao最多支持1个group by");
        }
        GroupBy groupBy = groupByList.get(0);
        String key = groupBy.getKey();
        /**String groupCountAlias = groupBy.getGroupCountAlias();*/
        String termsName = key + "_group";
        return AggregationBuilders.terms(termsName).field(key);
    }

    /**
     * 得到聚合的结果entity list
     *
     * @param searchResponse -
     * @param entityClass    -
     * @param groupByList    -
     * @return
     */
    static <T> List<T> getAggregationEntityList(SearchResponse searchResponse, Class<T> entityClass, List<GroupBy> groupByList) {
        if (groupByList.size() > 1) { //暂时只支持一个
            throw new DaoException("ElasticSearchBaseDao最多支持1个group by");
        }
        GroupBy groupBy = groupByList.get(0);
        String key = groupBy.getKey();
        String groupCountAlias = groupBy.getGroupCountAlias();
        String termsName = key + "_group";

        List<T> entityList = Lists.newArrayList();
        Terms terms = searchResponse.getAggregations().get(termsName);
        if (terms == null) {
            return entityList;
        }

        for (Terms.Bucket entry : terms.getBuckets()) {
            Map<String, Object> aggMap = Maps.newLinkedHashMap();
            aggMap.put(key, entry.getKey());
            if (StringUtils.isNotBlank(groupCountAlias)) {
                aggMap.put(groupCountAlias, entry.getDocCount());
            }
            entityList.add(BeanUtils.mapToBean(aggMap, entityClass));
        }
        return entityList;
    }

    static <T> T getEntity(SearchResponse searchResponse, Class<T> entityClass, boolean hasEsVersionFiled) {
        SearchHits searchHits = searchResponse.getHits();
        if (searchHits.getTotalHits() == MixedConstant.LONG_0) {
            return null;
        }

        SearchHit searchHit = searchHits.getHits()[MixedConstant.INT_0];
        String source = setEsVersion(searchHit, hasEsVersionFiled);
        return FastJson.jsonStr2Object(source, entityClass);
    }

    /**
     * 判断一个es entity是否带有version字段
     *
     * @param entityClass -
     * @return
     */
    static boolean hasEsVersionField(Class<?> entityClass) {
        try {
            return null != entityClass.getDeclaredField(ES_VERSION_FIELD_NAME);
        } catch (NoSuchFieldException e) {
        }
        return false;
    }

    /**
     * 设置version字段
     *
     * @param searchHit         -
     * @param hasEsVersionFiled -
     * @return
     */
    static String setEsVersion(SearchHit searchHit, boolean hasEsVersionFiled) {
        StringBuilder sb = new StringBuilder(searchHit.getSourceAsString());
        if (hasEsVersionFiled) {
            sb.deleteCharAt(sb.length() - MixedConstant.INT_1);//去掉最后一个}
            sb.append(",\"").append(ES_VERSION_FIELD_NAME).append("\":").append(searchHit.getVersion()).append("}");
        }
        return sb.toString();
    }

    /**
     * 设置version字段
     *
     * @param response          -
     * @param hasEsVersionFiled -
     * @return
     */
    static String setEsVersion(GetResponse response, boolean hasEsVersionFiled) {
        StringBuilder sb = new StringBuilder(response.getSourceAsString());
        if (hasEsVersionFiled) {
            sb.deleteCharAt(sb.length() - MixedConstant.INT_1);//去掉最后一个}
            sb.append(",\"").append(ES_VERSION_FIELD_NAME).append("\":").append(response.getVersion()).append("}");
        }
        return sb.toString();
    }

    /**
     * 设置version字段
     *
     * @param entity            -
     * @param version           -
     * @param hasEsVersionFiled -
     * @return
     */
    static void setEsVersion(Object entity, long version, boolean hasEsVersionFiled) {
        if (hasEsVersionFiled) {
            try {
                Field field = entity.getClass().getDeclaredField(ES_VERSION_FIELD_NAME);
                field.setAccessible(true);
                field.setLong(entity, version);
            } catch (Exception e) {
                throw new DaoException("cat not set version field for Entity[" + entity.getClass().getSimpleName() + "],please check field's type is long", e);
            }
        }
    }


    /**
     * 插入文档生成持久化的json字符串
     *
     * @param entity                       -
     * @param hasEsVersionFiled            -
     * @param notNeedTransientPropertyList -
     * @return
     */
    static String getSourceJsonStrWhenInsert(Object entity, boolean hasEsVersionFiled, List<String> notNeedTransientPropertyList) {
        if (hasEsVersionFiled) {
            notNeedTransientPropertyList.add(ES_VERSION_FIELD_NAME);
        }

        String sourceJsonStr;
        if (notNeedTransientPropertyList.isEmpty()) {
            sourceJsonStr = FastJson.object2JsonStrUseNullValue(entity);
        } else {
            sourceJsonStr = FastJson.object2JsonStrForIgnoreUseNullValue(entity, notNeedTransientPropertyList);
        }
        return sourceJsonStr;
    }

    /**
     * 修改文档生成持久化的json字符串
     *
     * @param update                       -
     * @param hasEsVersionFiled            -
     * @param notNeedTransientPropertyList -
     * @return
     */
    static String getSourceJsonStrWhenUpdate(Update update, boolean hasEsVersionFiled, List<String> notNeedTransientPropertyList) {
        if (hasEsVersionFiled) {
            notNeedTransientPropertyList.add(ES_VERSION_FIELD_NAME);
        }

        String sourceJsonStr;
        if (notNeedTransientPropertyList.isEmpty()) {
            sourceJsonStr = FastJson.object2JsonStrUseNullValue(update.getSetMap());
        } else {
            sourceJsonStr = FastJson.object2JsonStrForIgnoreUseNullValue(update.getSetMap(), notNeedTransientPropertyList);
        }
        return sourceJsonStr;
    }

    static String getIdSerializable(Serializable id) {
        if (id instanceof Long) {
            return Long.toString((Long) id);
        } else {
            return (String) id;
        }
    }

    static DaoException translateElasticSearchException(ElasticsearchException e) {
        /**
         * 按照惯例通常limit=0表示查询所有的,但es5.4.3默认根据index.max_result_window配置form+size不能超过10000
         * 所以这里要设置成很大的值让es抛出异常以便研发知道他没有得到期望的记录数
         * 官方提示：
         * {
         * "type": "query_phase_execution_exception",
         * "reason": "Result window is too large, from + size must be less than or equal to: [10000] but was [489308].
         * See the scroll api for a more efficient way to request large data sets. This limit can be set by changing the [index.max_result_window] index level setting."
         * }
         */
        StringBuilder sb = new StringBuilder();
        String errorMessage = e.toString();
        if (errorMessage.contains("max_result_window")) {
            sb.append("ElasticSearch查询结果限制[from + size不能超过index.max_result_window设置的值,默认是10000]");
            sb.append("官方错误:");
            sb.append(errorMessage);
        }
        throw new DaoException(sb.toString(), e);
    }

    public static String getIndexName(Class entityClass) {
        Document documentAnn = (Document) entityClass.getAnnotation(Document.class);
        return documentAnn.indexName();
    }

    public static String getTypeName(Class entityClass) {
        Document documentAnn = (Document) entityClass.getAnnotation(Document.class);
        return documentAnn.typeName();
    }

    /**
     * 校验entityClass必须符合框架的规范
     *
     * @param entityClass
     */
    public static void checkEntityClass(Class entityClass) {
        if (entityClass == null) {
            throw new DaoException("can not get the entity's Generic Type");
        }

        String entityClassName = entityClass.getName();
        if (!IdEntity.class.isAssignableFrom(entityClass)) {
            throw new DaoException("entity[" + entityClassName + "] must implements IdEntity");
        }

        Document tableAnnotation = (Document) entityClass.getAnnotation(Document.class);
        if (tableAnnotation == null) {
            throw new DaoException("entity[" + entityClassName + "] must have Document annotation");
        }

        Field[] fields = entityClass.getDeclaredFields();
        if (fields == null || fields.length == 0) {
            throw new DaoException("entity[" + entityClassName + "] must have least one Field");
        }

        int pkAnnotationCount = 0;
        String pkFieldTypeName = "";
        List<String> supportPKFieldType = Lists.newArrayList("java.lang.Integer", "java.lang.Long", "java.lang.String");
        for (Field field : fields) {
            if (DaoHelper.isFinalOrStatic(field)) {
                continue;
            }
            com.github.zhouyutong.zorm.dao.elasticsearch.annotation.Field columnAnnotation = field.getAnnotation(com.github.zhouyutong.zorm.dao.elasticsearch.annotation.Field.class);
            if (columnAnnotation == null) {
                throw new DaoException("entity[" + entityClassName + "]的字段[" + field.getName() + "]必须有Column注解");
            }

            PK pkAnnotation = field.getAnnotation(PK.class);
            if (pkAnnotation != null) {
                pkAnnotationCount++;
                pkFieldTypeName = field.getType().getName();
            }
        }
        if (pkAnnotationCount != 1) {
            throw new DaoException("entity[" + entityClassName + "] 有且只能有一个PK注解的字段");
        }
        if (!supportPKFieldType.contains(pkFieldTypeName)) {
            throw new DaoException("entity[" + entityClassName + "]的pk字段类型只能是Long,Integer,String其中之一");
        }
    }
}
