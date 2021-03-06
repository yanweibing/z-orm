package com.github.zhouyutong.zorm.dao.jdbc.codegenerate;

import com.google.common.base.Preconditions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * @Author zhouyutong
 * @Date 2016/9/18
 */
public class CodeGenrater {
    private JdbcTemplate jdbcTemplate;

    public CodeGenrater(String driverClassName, String dbUrl, String dbUserName, String dbPassword){
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource();
        dataSource.setSuppressClose(true);
        dataSource.setDriverClassName(driverClassName);
        dataSource.setUrl(dbUrl);
        dataSource.setUsername(dbUserName);
        dataSource.setPassword(dbPassword);

        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * 生成mybatis对应的dao、entity、mapper映射文件
     *
     * @param modulePackagePath  - 模块的绝对路径，如D:/MyWorkSpace/ehr-new/service-provider/src/main/java/com/ziroom/ehr/provider/
     * @param modulePackageName  - 模块的包名，如com.ziroom.ehr.provider.emp
     * @param tableName          - 表名，如t_emp
     */
    public void generateDaoAndEntityAndMapperOfMybatis(String modulePackagePath, String modulePackageName, String tableName) {
        Preconditions.checkNotNull(modulePackagePath);
        Preconditions.checkNotNull(modulePackageName);
        Preconditions.checkNotNull(tableName);
        new GenerateHelper(modulePackagePath, modulePackageName, tableName, jdbcTemplate).genterate();

    }

    /**
     * 生成jdbcTemplate对应的dao、entity映射文件
     *
     * @param modulePackagePath  - 模块的绝对路径，如D:/MyWorkSpace/ehr-new/service-provider/src/main/java/com/ziroom/ehr/provider/
     * @param modulePackageName  - 模块的包名，如com.ziroom.ehr.provider.emp
     * @param tableName          - 表名，如t_emp
     */
    public void generateDaoAndEntityOfJdbc(String modulePackagePath, String modulePackageName, String tableName) {
        Preconditions.checkNotNull(modulePackagePath);
        Preconditions.checkNotNull(modulePackageName);
        Preconditions.checkNotNull(tableName);
        new GenerateHelper(modulePackagePath, modulePackageName, tableName, jdbcTemplate).genterate();
    }
}
