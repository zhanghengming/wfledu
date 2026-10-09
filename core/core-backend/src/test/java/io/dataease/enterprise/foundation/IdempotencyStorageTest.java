package io.dataease.enterprise.foundation;

import io.dataease.dao.auto.entity.DeStandaloneVersion;
import io.dataease.enterprise.permission.persistence.EnterpriseIdempotency;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

class IdempotencyStorageTest {
    @BeforeAll static void guard() throws Exception { FoundationMigrationTest.guardedPrivateConnection(); }
    private static void fixture(String name,java.util.function.Consumer<PermissionStorageFixture.Fixture> action) {
        PermissionStorageFixture.use(name,List.of(EnterpriseIdempotency.class),f -> {
            f.add("v46",new EnterpriseGrantSqlBlock(f.jdbc()));action.accept(f);
        });
    }
    private static void migrate(PermissionStorageFixture.Fixture f) {f.add("v47",new EnterpriseIdempotencySqlBlock(f.jdbc()));f.run();}
    private static void row(JdbcTemplate jdbc,long id,long tenant,long user,String operation,String key,String state,String metadata) {
        jdbc.update("INSERT INTO de_ent_idempotency(id,tenant_id,principal_kind,user_id,operation,idempotency_key,request_digest,state,result_metadata,created_at,expires_at) VALUES(?,?,'USER',?,?,?,?,?,?,'2026-10-08 00:00:00','2026-10-09 00:00:00')",
                id,tenant,user,operation,key,new byte[32],state,metadata);
    }
    @Test void sevenStepEmptyPlanAndRepeatHaveNoRecordsOrEmbeddedAppDependency() {
        fixture("idempotempty",f -> {migrate(f);f.run();new FoundationSchemaVerifier(f.jdbc()).run(null);
            assertThat(f.versions().findRecords()).extracting(DeStandaloneVersion::getVersion).containsExactly("4.7","4.6","4.5","4.4","4.3","4.2","4.1");
            assertThat(f.versions().findRecords()).extracting(DeStandaloneVersion::getSuccess).containsOnly(true);
            assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM de_ent_idempotency",Long.class)).isZero();
            assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='de_ent_embed_app'",Long.class)).isZero();
            assertThat(FoundationSchemaV47.ADDITIONS.getFirst().columns()).hasSize(18);
            assertThat(FoundationSchemaV47.ADDITIONS.getFirst().foreignKeys()).hasSize(4);
        });
    }
    @Test void seededV46UpgradeRetainsPolicyRowsAndSuccessfulHistory() {
        fixture("idempotupgrade",f -> {f.run();PermissionStorageFixture.seed(f.jdbc());
            f.jdbc().update("INSERT INTO de_ent_grant(id,tenant_id,subject_id,policy_kind,resource_type,resource_id,action,effect,school_scope_kind) VALUES(3001,10,13,'DATA_ACCESS','DATASET',1001,'VIEW','ALLOW','EXPLICIT')");
            f.jdbc().update("INSERT INTO de_ent_grant_school(id,tenant_id,grant_id,school_id) VALUES(5001,10,3001,101)");
            var before=PermissionStorageFixture.snapshot(f.jdbc(),FoundationSchemaV46.TABLES);
            var history=f.versions().findRecords().stream().map(v->List.of(v.getId(),v.getVersion(),v.getSuccess())).toList();
            migrate(f);f.run();new FoundationSchemaVerifier(f.jdbc()).run(null);
            assertThat(PermissionStorageFixture.snapshot(f.jdbc(),FoundationSchemaV46.TABLES)).isEqualTo(before);
            assertThat(f.versions().findRecords().subList(1,7).stream().map(v->List.of(v.getId(),v.getVersion(),v.getSuccess())).toList()).isEqualTo(history);
            assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM de_ent_idempotency",Long.class)).isZero();
        });
    }
    @Test void committedIdempotencyDdlFailureRetainsLedgerAndRetriesWithoutLosingRows() {
        fixture("idempotretry",f -> {f.run();PermissionStorageFixture.seed(f.jdbc());var before=PermissionStorageFixture.snapshot(f.jdbc(),FoundationSchemaV46.TABLES);
            var interrupted=spy(f.jdbc());
            doAnswer(call->{String sql=call.getArgument(0);f.jdbc().execute(sql);if(sql.startsWith("CREATE TABLE `de_ent_idempotency`"))throw new IllegalStateException("Synthetic committed idempotency interruption");return null;}).when(interrupted).execute(anyString());
            f.add("v47",new EnterpriseIdempotencySqlBlock(interrupted));
            assertThatThrownBy(f::run).isInstanceOf(RuntimeException.class).hasMessageContaining("4.7");
            assertThat(f.versions().findRecords().getFirst().getSuccess()).isFalse();
            assertThat(FoundationSchema.exists(f.jdbc(),FoundationSchemaV47.ADDITIONS.getFirst())).isTrue();
            f.add("v47",new EnterpriseIdempotencySqlBlock(f.jdbc()));f.run();f.run();new FoundationSchemaVerifier(f.jdbc()).run(null);
            assertThat(f.versions().findRecords()).extracting(DeStandaloneVersion::getSuccess).containsExactly(true,false,true,true,true,true,true,true);
            assertThat(f.versions().findRecords()).extracting(DeStandaloneVersion::getVersion).containsExactly("4.7","4.7","4.6","4.5","4.4","4.3","4.2","4.1");
            assertThat(PermissionStorageFixture.snapshot(f.jdbc(),FoundationSchemaV46.TABLES)).isEqualTo(before);
        });
    }
    @Test void generationAndDefaultDriftRemainUnrepairedAndFailedHistoryIsPreserved() {
        for(String kind:List.of("expression","default")) fixture("idempotdrift",f -> {f.run();var table=FoundationSchemaV47.ADDITIONS.getFirst();
            String ddl=kind.equals("expression")?table.ddl().replace("coalesce(`user_id`,`app_id`)","coalesce(`app_id`,`user_id`)"):table.ddl().replace("DEFAULT 'IN_PROGRESS'","DEFAULT 'in_progress'");
            f.jdbc().execute(ddl);var before=f.jdbc().queryForMap("SHOW CREATE TABLE de_ent_idempotency");
            f.add("v47",new EnterpriseIdempotencySqlBlock(f.jdbc()));
            assertThatThrownBy(f::run).isInstanceOf(RuntimeException.class).hasMessageContaining("4.7");
            assertThat(f.versions().findRecords().getFirst().getSuccess()).isFalse();
            assertThat(f.jdbc().queryForMap("SHOW CREATE TABLE de_ent_idempotency")).isEqualTo(before);
        });
    }
    @Test void userOnlyPrincipalOperationsKeysExpiryAndForeignReferencesReject() {
        fixture("idempotinvalid",f -> {migrate(f);PermissionStorageFixture.seed(f.jdbc());row(f.jdbc(),9001,10,1,"PERMISSIONS_BATCH","Synthetic-Key-0001","DONE","{}");
            for(String update:List.of("principal_kind='APP'","principal_kind='user'","user_id=NULL","user_id=0","user_id=999","app_id=1001","tenant_id=999",
                    "operation='TICKETS_CREATE'","idempotency_key='short'","idempotency_key='Synthetic Key-0001'","idempotency_key='中文SyntheticKey0001'",
                    "idempotency_key=CONCAT('Synthetic-Key-0001',CHAR(10))","idempotency_key=CONCAT('Synthetic-Key-0001',CHAR(13))","idempotency_key=CONCAT('Synthetic-Key-0001',CHAR(9))",
                    "state='done'","expires_at=created_at","expires_at='2026-10-07 00:00:00'","version=0")) {
                PermissionStorageFixture.integrity(()->f.jdbc().update("UPDATE de_ent_idempotency SET "+update+" WHERE id=9001"),3819,1452,1216,1048);
            }
            assertThat(f.jdbc().queryForObject("SELECT principal_key FROM de_ent_idempotency WHERE id=9001",Long.class)).isEqualTo(1L);
        });
    }
    @Test void keyScopeSeparatesGroupUserOperationAndExactCaseWhileDuplicatesReject() {
        fixture("idempotnatural",f -> {migrate(f);PermissionStorageFixture.seed(f.jdbc());
            row(f.jdbc(),9001,10,1,"PERMISSIONS_BATCH","Synthetic-Key-0001","DONE","{}");
            row(f.jdbc(),9002,20,1,"PERMISSIONS_BATCH","Synthetic-Key-0001","DONE","{}");
            row(f.jdbc(),9003,10,2,"PERMISSIONS_BATCH","Synthetic-Key-0001","DONE","{}");
            row(f.jdbc(),9004,10,1,"ADMIN_CAPABILITIES_BATCH","Synthetic-Key-0001","DONE","{}");
            row(f.jdbc(),9005,10,1,"PERMISSIONS_BATCH","synthetic-Key-0001","DONE","{}");
            PermissionStorageFixture.integrity(()->row(f.jdbc(),9006,10,1,"PERMISSIONS_BATCH","Synthetic-Key-0001","DONE","{}"),1062);
            assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM de_ent_idempotency",Long.class)).isEqualTo(5);
        });
    }
    @Test void doneMetadataRequiresBoundedJsonObjectIncludingUtf8Storage() {
        fixture("idempotjson",f -> {migrate(f);PermissionStorageFixture.seed(f.jdbc());
            for(String metadata:new String[]{null,"null","[]","\"string\"","{\"v\":\""+ "x".repeat(65537)+"\"}","{\"v\":\""+ "财".repeat(21845)+"\"}"}) {
                PermissionStorageFixture.integrity(()->row(f.jdbc(),9001,10,1,"PERMISSIONS_BATCH","Synthetic-Key-0001","DONE",metadata),3819);
            }
            row(f.jdbc(),9001,10,1,"PERMISSIONS_BATCH","Synthetic-Key-0001","DONE","{\"v\":\""+ "x".repeat(65500)+"\"}");
            assertThat(f.jdbc().queryForObject("SELECT OCTET_LENGTH(CAST(result_metadata AS CHAR CHARACTER SET utf8mb4)) FROM de_ent_idempotency",Long.class)).isLessThanOrEqualTo(65536L);
        });
    }
    private static EnterpriseIdempotency entity(long id) {
        var row=new EnterpriseIdempotency();row.setId(id);row.setTenantId(10L);row.setUserId(1L);row.setOperation("PERMISSIONS_BATCH");row.setIdempotencyKey("Synthetic-Key-0001");
        row.setRequestDigest(new byte[32]);row.setState("DONE");row.setResultMetadata("{\"results\":[{\"id\":\"3001\",\"version\":\"1\"}],\"committedEpoch\":\"2\"}");
        var now=LocalDateTime.of(2026,10,8,0,0);row.setCreatedAt(now);row.setUpdatedAt(now);row.setExpiresAt(now.plusHours(24));row.setCreatedBy(1L);row.setUpdatedBy(1L);return row;
    }
    @Test void jpaGeneratedPrincipalIsReadOnlyAndDigestHasExactDefensiveCopies() {
        fixture("idempotjpa",f -> {migrate(f);PermissionStorageFixture.seed(f.jdbc());
            var row=entity(9001);byte[] supplied=new byte[32];supplied[0]=1;row.setRequestDigest(supplied);supplied[0]=2;
            byte[] returned=row.getRequestDigest();returned[0]=3;assertThat(row.getRequestDigest()[0]).isEqualTo((byte)1);
            for(byte[] invalid:new byte[][]{null,new byte[31],new byte[33]}) assertThatThrownBy(()->row.setRequestDigest(invalid)).isInstanceOf(IllegalArgumentException.class);
            try(var em=f.factory().createEntityManager()) {em.getTransaction().begin();ReflectionTestUtils.setField(row,"principalKey",999L);em.persist(row);em.flush();em.refresh(row);
                assertThat(row.getPrincipalKey()).isEqualTo(1L);ReflectionTestUtils.setField(row,"principalKey",999L);row.setResponseRef("safe-reference");em.flush();em.clear();
                var loaded=em.find(EnterpriseIdempotency.class,9001L);assertThat(loaded.getPrincipalKey()).isEqualTo(1L);
                assertThat(loaded.getRequestDigest()[0]).isEqualTo((byte)1);assertThat(loaded.getResultMetadata()).contains("3001");em.getTransaction().commit();}
            var field=EnterpriseIdempotency.class.getDeclaredFields();
            assertThat(java.util.Arrays.stream(field).filter(v->v.getName().equals("principalKey")).findFirst().orElseThrow().getAnnotation(jakarta.persistence.Column.class).insertable()).isFalse();
        });
    }
    @Test void jpaOptimisticConflictAndManagedExplicitClearArePersistedCorrectly() {
        fixture("idempotcas",f -> {migrate(f);PermissionStorageFixture.seed(f.jdbc());
            try(var em=f.factory().createEntityManager()) {em.getTransaction().begin();var row=entity(9001);row.setResponseRef("safe-reference");em.persist(row);em.getTransaction().commit();}
            try(var first=f.factory().createEntityManager();var second=f.factory().createEntityManager()) {
                first.getTransaction().begin();second.getTransaction().begin();var a=first.find(EnterpriseIdempotency.class,9001L);var b=second.find(EnterpriseIdempotency.class,9001L);
                a.setResponseRef(null);first.getTransaction().commit();b.setResponseRef("stale");
                assertThatThrownBy(second::flush).isInstanceOf(OptimisticLockException.class);second.getTransaction().rollback();}
            try(var em=f.factory().createEntityManager()) {var row=em.find(EnterpriseIdempotency.class,9001L);assertThat(row.getResponseRef()).isNull();assertThat(row.getVersion()).isEqualTo(2L);}
        });
    }
    @Test void springTransactionFailureRollsBackResultPolicyAuditAndRevisionTogether() {
        fixture("idempotrollback",f -> {migrate(f);PermissionStorageFixture.seed(f.jdbc());
            var manager=f.context().getBean(org.springframework.orm.jpa.JpaTransactionManager.class);
            assertThatThrownBy(()->new TransactionTemplate(manager).execute(status -> {
                var em=EntityManagerFactoryUtils.getTransactionalEntityManager(f.factory());assertThat(em).isNotNull();
                em.persist(entity(9001));em.flush();
                em.createNativeQuery("INSERT INTO de_ent_grant(id,tenant_id,subject_id,policy_kind,resource_type,resource_id,action,effect,school_scope_kind) VALUES(3001,10,13,'DATA_ACCESS','DATASET',1001,'VIEW','ALLOW','EXPLICIT')").executeUpdate();
                em.createNativeQuery("UPDATE de_ent_tenant SET access_epoch=2 WHERE id=10").executeUpdate();
                em.createNativeQuery("INSERT INTO de_ent_audit_event(id,event_scope,tenant_id,actor_kind,actor_user_id,event_type,result_code,trace_id,access_epoch,details) VALUES(7001,'TENANT',10,'USER',1,'W04_STORAGE_PROBE','SUCCESS','synthetic-storage-probe',2,'{}')").executeUpdate();
                throw new IllegalStateException("Synthetic failure after all result writes");
            })).isInstanceOf(IllegalStateException.class).hasMessageContaining("Synthetic failure");
            for(String name:List.of("de_ent_idempotency","de_ent_grant","de_ent_audit_event")) assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM "+name,Long.class)).isZero();
            assertThat(f.jdbc().queryForObject("SELECT access_epoch FROM de_ent_tenant WHERE id=10",Long.class)).isEqualTo(1L);
        });
    }
    @Test void concurrentSameKeyHasExactlyOneCommittedResult() {
        fixture("idempotconcurrent",f -> {migrate(f);PermissionStorageFixture.seed(f.jdbc());
            var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
            try {
                var tasks=new java.util.ArrayList<Future<Integer>>();
                for(long id:new long[]{9001,9002}) tasks.add(pool.submit(()->{ready.countDown();assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();
                    try {row(f.jdbc(),id,10,1,"PERMISSIONS_BATCH","Synthetic-Key-0001","DONE","{}");return 0;}
                    catch(org.springframework.dao.DataAccessException error) {return ((java.sql.SQLException)error.getRootCause()).getErrorCode();}}));
                assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
                var results=new java.util.ArrayList<Integer>();for(var task:tasks)results.add(task.get(20,TimeUnit.SECONDS));
                assertThat(results).containsExactlyInAnyOrder(0,1062);
                assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM de_ent_idempotency WHERE state='DONE'",Long.class)).isEqualTo(1);
                assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM de_ent_idempotency WHERE state<>'DONE'",Long.class)).isZero();
            } catch(InterruptedException | ExecutionException | TimeoutException error) {throw new IllegalStateException("Synthetic concurrency fixture failed",error);}
            finally {pool.shutdownNow();}
        });
    }
    @Test void oldSixStepPlanCannotDowngradeSuccessfulV47History() {
        fixture("idempotdowngrade",f -> {migrate(f);var before=f.versions().findRecords().stream().map(v->List.of(v.getId(),v.getVersion(),v.getSuccess())).toList();
            f.remove("v47");assertThatThrownBy(f::run).isInstanceOf(IllegalStateException.class);
            assertThat(f.versions().findRecords().stream().map(v->List.of(v.getId(),v.getVersion(),v.getSuccess())).toList()).isEqualTo(before);
            new FoundationSchemaVerifier(f.jdbc()).run(null);
            assertThatThrownBy(()->FoundationSchemaV47.ADDITIONS.clear()).isInstanceOf(UnsupportedOperationException.class);
        });
    }
}
