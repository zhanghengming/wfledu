package io.dataease.enterprise.foundation;

import io.dataease.dao.auto.entity.DeStandaloneVersion;
import io.dataease.extensions.datasource.utils.SpringContextUtil;
import io.dataease.initSql.SqlBlock;
import io.dataease.listener.InitSqlListener;
import io.dataease.migrationfixture.W03VersionRepository;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import java.util.List;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;

/** Retained synthetic MySQL fixtures, production version Repository/aspect/listener and JPA filter. */
final class PermissionStorageFixture {
    private PermissionStorageFixture() { }
    static void use(String scenario, List<Class<?>> entities, Consumer<Fixture> check) {
        var jdbc = FoundationMigrationTest.fresh(scenario);
        var types = new java.util.ArrayList<String>();types.add(DeStandaloneVersion.class.getName());
        entities.forEach(type -> types.add(type.getName()));
        var previous = SpringContextUtil.getApplicationContext();
        try {
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(HibernateJpaAutoConfiguration.class))
                    .withUserConfiguration(EnterpriseJpaConfiguration.class,EnterpriseMigrationEvolutionTest.RepositoryConfiguration.class)
                    .withBean(DataSource.class,jdbc::getDataSource)
                    .withBean(PersistenceManagedTypes.class,()->PersistenceManagedTypes.of(types.toArray(String[]::new)))
                    .withBean("v41",SqlBlock.class,()->new EnterpriseFoundationSqlBlock(jdbc))
                    .withBean("v42",SqlBlock.class,()->new EnterpriseAuditSqlBlock(jdbc))
                    .withBean("v43",SqlBlock.class,()->new EnterpriseAuthoritySqlBlock(jdbc))
                    .withBean("v44",SqlBlock.class,()->new EnterpriseCredentialSqlBlock(jdbc))
                    .withBean("v45",SqlBlock.class,()->new EnterpriseResourceSqlBlock(jdbc))
                    .withPropertyValues("enterprise.foundation.enabled=true","spring.jpa.hibernate.ddl-auto=update")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var source = (GenericApplicationContext)context.getSourceApplicationContext();
                        new SpringContextUtil().setApplicationContext(source);
                        check.accept(new Fixture(jdbc,context.getBean(W03VersionRepository.class),source,context.getBean(EntityManagerFactory.class)));
                    });
        } finally { new SpringContextUtil().setApplicationContext(previous); }
    }
    record Fixture(JdbcTemplate jdbc,W03VersionRepository versions,GenericApplicationContext context,EntityManagerFactory factory) {
        void add(String name,SqlBlock block) {
            if (context.containsBeanDefinition(name)) context.removeBeanDefinition(name);
            context.registerBean(name,SqlBlock.class,()->block);
        }
        void remove(String name) { context.removeBeanDefinition(name); }
        void run() {
            var listener = new InitSqlListener();
            ReflectionTestUtils.setField(listener,"deStandaloneVersionRepository",versions);
            listener.run(null);
        }
    }
    static void seed(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO de_ent_user(id,username,display_name) VALUES(1,'synthetic-a','Synthetic A'),(2,'synthetic-b','Synthetic B')");
        jdbc.update("INSERT INTO de_ent_tenant(id,code,name) VALUES(10,'A','Synthetic A'),(20,'B','Synthetic B')");
        jdbc.update("INSERT INTO de_ent_tenant_member(id,tenant_id,user_id) VALUES(11,10,1),(21,20,2)");
        jdbc.update("INSERT INTO de_ent_org(id,tenant_id,kind,name,school_code) VALUES(101,10,'SCHOOL','Synthetic A','A001'),(201,20,'SCHOOL','Synthetic B','B001')");
        jdbc.update("INSERT INTO de_ent_role(id,tenant_id,code,name) VALUES(12,10,'A','Synthetic A'),(22,20,'B','Synthetic B')");
        jdbc.update("INSERT INTO de_ent_subject(id,tenant_id,subject_type,member_id) VALUES(13,10,'USER',11),(23,20,'USER',21)");
        jdbc.update("INSERT INTO de_ent_resource(id,tenant_id,resource_type) VALUES(1001,10,'DATASET'),(2001,20,'DATASET'),(1002,10,'DASHBOARD'),(2002,20,'DASHBOARD')");
    }
    static void integrity(Runnable command,int... codes) {
        var failure = catchThrowable(command::run);
        assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var root = ((org.springframework.dao.DataAccessException)failure).getRootCause();
        assertThat(root).isInstanceOf(java.sql.SQLException.class);
        assertThat(((java.sql.SQLException)root).getErrorCode()).isIn(java.util.Arrays.stream(codes).boxed().toArray(Integer[]::new));
    }
    static java.util.Map<String,List<java.util.Map<String,Object>>> snapshot(JdbcTemplate jdbc,List<FoundationSchema.Table> tables) {
        var rows = new java.util.LinkedHashMap<String,List<java.util.Map<String,Object>>>();
        for (var table : tables) rows.put(table.name(),jdbc.queryForList("SELECT * FROM `"+table.name()+"` ORDER BY id"));
        return rows;
    }
    static void reservedView(JdbcTemplate jdbc) {
        String database = jdbc.queryForObject("SELECT DATABASE()",String.class);
        assertThat(database).matches("de_phase1_w03_[a-z]+_[a-f0-9]{12}");
        assertThat(jdbc.queryForObject("SELECT marker FROM w03_test_owner",String.class)).isEqualTo("synthetic-only-retain-no-drop");
        // CREATE VIEW uses the already authorized lab migration account; do not broaden fixture grants.
        String script = """
                import pathlib,sys,importlib.util
                r=pathlib.Path('/home/data_dev_zhm/dataease-phase1-test/w02-security')
                s=importlib.util.spec_from_file_location('b',r/'source/tools/phase1/verify-database-boundary.py')
                b=importlib.util.module_from_spec(s);s.loader.exec_module(b)
                db=sys.argv[1]
                import re
                assert re.fullmatch(r'de_phase1_w03_[a-z]+_[a-f0-9]{12}',db)
                p=b.query('root','SELECT @@port;SELECT marker FROM '+db+'.w03_test_owner;')
                assert p.returncode==0 and p.stdout.strip()=='13306\\nsynthetic-only-retain-no-drop'
                p=b.query('root','CREATE VIEW '+db+'.de_ent_undeclared_grant_probe AS SELECT id FROM '+db+'.de_ent_grant;')
                assert p.returncode==0
                print('VIEW_CREATED')
                """;
        try {
            var process = new ProcessBuilder("python3","-B","-c",script,database).redirectErrorStream(true).start();
            try {
                assertThat(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                String output = new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                assertThat(process.exitValue()).isZero();assertThat(output.strip()).isEqualTo("VIEW_CREATED");
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        } catch (java.io.IOException | InterruptedException error) {
            throw new IllegalStateException("Synthetic view fixture failed",error);
        }
    }

}
