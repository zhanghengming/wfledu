package io.dataease.enterprise.foundation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataease.auth.interceptor.CorsConfig;
import io.dataease.enterprise.bootstrap.ManagementConfiguration;
import io.dataease.enterprise.identity.manage.PasswordCodec;
import io.dataease.enterprise.management.server.IdentityManagementServer;
import io.dataease.enterprise.management.server.GroupManagementServer;
import io.dataease.exception.GlobalExceptionHandler;
import io.dataease.extensions.datasource.utils.SpringContextUtil;
import io.dataease.listener.InitSqlListener;
import io.dataease.utils.IDUtils;
import io.dataease.utils.SnowFlake;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Real loopback Tomcat, production filter/API/initializer/migration and real MySQL. */
class ManagementHttpBoundaryTest {
    private static JdbcTemplate jdbc;
    private static final AtomicInteger PORT=new AtomicInteger();
    private static CountDownLatch blocked,release;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @SpringBootConfiguration @EnableAutoConfiguration
    @Import({EnterpriseJpaConfiguration.class,FoundationConfiguration.class,ManagementConfiguration.class,IdentityManagementServer.class,GroupManagementServer.class,io.dataease.enterprise.management.server.ResourceOwnershipServer.class,io.dataease.enterprise.management.server.RoleManagementServer.class,io.dataease.enterprise.permission.server.PermissionManagementServer.class,
            CorsConfig.class,GlobalExceptionHandler.class,io.dataease.enterprise.management.server.ManagementExceptionHandler.class,EnterpriseMigrationEvolutionTest.RepositoryConfiguration.class,
            SpringContextUtil.class,InitSqlListener.class,IDUtils.class,SnowFlake.class})
    static class App {
        @Bean org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes managedTypes(){
            return org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes.of(
                    io.dataease.enterprise.identity.persistence.EnterpriseUser.class.getName(),io.dataease.enterprise.identity.persistence.EnterpriseTenant.class.getName(),
                    io.dataease.enterprise.identity.persistence.EnterpriseTenantMember.class.getName(),io.dataease.enterprise.identity.persistence.EnterpriseOrganization.class.getName(),
                    io.dataease.enterprise.identity.persistence.EnterpriseUserCredential.class.getName(),io.dataease.enterprise.identity.persistence.EnterpriseLoginSession.class.getName(),
                    io.dataease.enterprise.identity.persistence.EnterprisePlatformQualification.class.getName(),io.dataease.enterprise.audit.persistence.EnterpriseAuditEvent.class.getName(),
                    io.dataease.dao.auto.entity.DeStandaloneVersion.class.getName(),
                    io.dataease.enterprise.management.persistence.EnterpriseSubject.class.getName(),io.dataease.enterprise.management.persistence.EnterpriseAdminGrant.class.getName(),
                    io.dataease.enterprise.management.persistence.EnterpriseOrgMember.class.getName(),io.dataease.enterprise.management.persistence.EnterpriseRole.class.getName(),
                    io.dataease.enterprise.management.persistence.EnterpriseRoleAssignment.class.getName(),io.dataease.enterprise.management.persistence.EnterpriseAssignmentSchool.class.getName(),io.dataease.enterprise.management.persistence.EnterpriseResource.class.getName(),io.dataease.dao.auto.entity.DataVisualizationInfo.class.getName(),io.dataease.dao.auto.entity.CoreDatasetGroup.class.getName(),io.dataease.enterprise.permission.persistence.EnterpriseGrant.class.getName(),io.dataease.enterprise.permission.persistence.EnterpriseGrantSchool.class.getName(),io.dataease.enterprise.permission.persistence.EnterpriseIdempotency.class.getName());
        }
        @Bean DataSource dataSource(){return jdbc.getDataSource();}
        @Bean JdbcTemplate jdbcTemplate(){
            var intercepted=spy(jdbc);
            doAnswer(call->{String sql=call.getArgument(0);
                if(sql.startsWith("CREATE TABLE `de_ent_user_credential`")){blocked.countDown();if(!release.await(30,TimeUnit.SECONDS))throw new IllegalStateException("Migration release timed out");}
                jdbc.execute(sql);return null;}).when(intercepted).execute(anyString());return intercepted;
        }
        @Bean ApplicationListener<WebServerInitializedEvent> port(){return event->PORT.set(event.getWebServer().getPort());}
    }
    @BeforeAll static void guard() throws Exception{FoundationMigrationTest.guardedPrivateConnection();}
    private record Fixture(ConfigurableApplicationContext context,int port,JdbcTemplate jdbc) {
        JsonNode post(String path,String body,String token,String...headers){return request(port,path,body,token,headers);}
        String login(String user,String password){return post("auth/login","{\"username\":\""+user+"\",\"password\":\""+password+"\"}",null).path("data").path("credential").asText();}
        String operator(){String token=login("operator","SyntheticPassword-123");
            assertThat(post("auth/password","{\"previousPassword\":\"SyntheticPassword-123\",\"newPassword\":\"ReplacementPassword-123\"}",token).path("code").asInt()).isZero();
            return login("operator","ReplacementPassword-123");}
        String user(String operator,String name){var response=post("users/create","{\"username\":\""+name+"\",\"displayName\":\""+name+"\",\"temporaryPassword\":\"SyntheticPassword-123\"}",operator);code(response,0);return response.path("data").path("id").asText();}
        Group group(String operator,String user,String code){String userId=user(operator,user);
            var response=post("tenants/create","{\"code\":\""+code+"\",\"name\":\""+code+"\",\"administratorUserId\":\""+userId+"\"}",operator);code(response,0);
            String tenant=response.path("data").path("id").asText(),member=response.path("data").path("administratorMemberId").asText();
            String token=login(user,"SyntheticPassword-123");code(post("auth/password","{\"previousPassword\":\"SyntheticPassword-123\",\"newPassword\":\"ReplacementPassword-123\"}",token),0);
            token=login(user,"ReplacementPassword-123");code(post("context/switch","{\"tenantId\":\""+tenant+"\",\"expectedVersion\":\"1\"}",token),0);
            return new Group(userId,member,tenant,token);
        }
        String school(Group group,String code){var response=post("organizations/save","{\"mode\":\"CREATE\",\"kind\":\"SCHOOL\",\"name\":\"School\",\"schoolCode\":\""+code+"\",\"status\":\"ACTIVE\"}",group.token());code(response,0);return response.path("data").path("id").asText();}
        void member(){
            jdbc.update("INSERT INTO de_ent_user(id,username,display_name,status) VALUES(2,'member','Member','ACTIVE')");
            jdbc.update("INSERT INTO de_ent_user_credential(id,user_id,encoded_hash,password_changed_at,must_reset) VALUES(3,2,?,UTC_TIMESTAMP(6),0)",new PasswordCodec().encode("SyntheticPassword-123".toCharArray()));
            jdbc.update("INSERT INTO de_ent_tenant(id,code,name,status) VALUES(10,'A','A','ACTIVE'),(20,'B','B','ACTIVE')");
            jdbc.update("INSERT INTO de_ent_tenant_member(id,tenant_id,user_id,status) VALUES(100,10,2,'ACTIVE')");
        }
    }
    private record Group(String userId,String memberId,String tenantId,String token){@Override public String toString(){return "Synthetic group [credential redacted]";}}
    private void fixture(String scenario,boolean testBlocked,Consumer<Fixture> check){fixture(scenario,testBlocked,1,check);}
    private void fixture(String scenario,boolean testBlocked,int threads,Consumer<Fixture> check){
        jdbc=FoundationMigrationTest.fresh(scenario);PORT.set(0);blocked=new CountDownLatch(1);release=new CountDownLatch(1);
        var previous=SpringContextUtil.getApplicationContext();Path input=null;ConfigurableApplicationContext context=null;
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            input=Files.createTempFile("w03-bootstrap-http-",".json");Files.setPosixFilePermissions(input,PosixFilePermissions.fromString("rw-------"));
            Files.writeString(input,"{\"username\":\"operator\",\"displayName\":\"Operator\",\"password\":\"SyntheticPassword-123\",\"qualifications\":[\"PLATFORM_OPERATE\",\"GROUP_READ_ALL\"]}");
            var app=new SpringApplication(App.class);app.setRegisterShutdownHook(false);
            final Path privateInput=input;
            String database=jdbc.queryForObject("SELECT DATABASE()",String.class);
            if(database==null || !database.matches("de_phase1_w03_[a-z]+_[a-f0-9]{12}"))throw new IllegalStateException("Unexpected HTTP test database");
            var future=executor.submit(()->app.run("--spring.config.name=w03_http_test","--spring.main.banner-mode=off","--server.address=127.0.0.1","--server.port=0",
                    "--logging.file.path=/home/data_dev_zhm/dataease-phase1-test/w02-security/logs/http-"+database,
                    "--server.tomcat.threads.max="+threads,"--server.tomcat.threads.min-spare=1","--spring.jpa.hibernate.ddl-auto=update","--spring.jpa.open-in-view=true",
                    "--enterprise.foundation.enabled=true","--enterprise.management.enabled=true","--enterprise.management.bootstrap-file="+privateInput,
                    "--dataease.machine-id=30","--logging.level.root=ERROR"));
            boolean reached=false;
            for(int attempt=0;attempt<30;attempt++){if(blocked.await(1,TimeUnit.SECONDS)){reached=true;break;}if(future.isDone()){future.get();break;}}
            assertThat(reached).isTrue();assertThat(PORT.get()).isPositive();
            if(testBlocked){
                assertThat(request(PORT.get(),"auth/login","{\"username\":\"operator\",\"password\":\"SyntheticPassword-123\"}",null).path("code").asInt()).isEqualTo(60005);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='de_ent_user_credential'",Long.class)).isZero();
            }
            release.countDown();context=future.get(45,TimeUnit.SECONDS);check.accept(new Fixture(context,PORT.get(),jdbc));
            new FoundationSchemaVerifier(jdbc).run(null);
        }catch(Exception failure){throw new AssertionError("Real management HTTP fixture failed",failure);}
        finally{release.countDown();if(context!=null)context.close();new SpringContextUtil().setApplicationContext(previous);if(input!=null)try{Files.delete(input);}catch(java.io.IOException failure){throw new AssertionError("Private input cleanup failed",failure);}}
    }
    private static JsonNode request(int port,String path,String body,String token,String...headers){
        try{
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+(path.startsWith("/de2api/")?path:"/de2api/api/enterprise/v1/"+path))).timeout(Duration.ofSeconds(10))
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            if(token!=null)builder.header("Authorization","Bearer "+token);for(int i=0;i<headers.length;i+=2)builder.header(headers[i],headers[i+1]);
            var result=HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");return JSON.readTree(result.body());
        }catch(Exception failure){throw new AssertionError("Real management HTTP request failed",failure);}
    }
    private static void code(JsonNode response,int code){assertThat(response.path("code").asInt(-1)).isEqualTo(code);}
    @Test void realLoginRefusesDuringMigrationAndOpensOnlyAfterInitialization(){fixture("httpreadiness",true,f->{
        String token=f.login("operator","SyntheticPassword-123");assertThat(token).matches("[A-Za-z0-9_-]{43}");
        code(f.post("context/current","{}",token),70001);code(f.post("auth/logout","{}",token),0);code(f.post("auth/logout","{}",token),20001);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_audit_event WHERE event_type='PLATFORM_INITIALIZED'",Long.class)).isEqualTo(1);
    });}
    @Test void strictJsonRejectsUnknownDuplicateTypeTrailingAndOversizedBodies(){fixture("httpjson",false,f->{
        for(String body:new String[]{"{\"username\":\"operator\",\"password\":\"SyntheticPassword-123\",\"tenantId\":\"10\"}",
                "{\"username\":\"operator\",\"username\":\"operator\",\"password\":\"SyntheticPassword-123\"}","{\"username\":2,\"password\":\"SyntheticPassword-123\"}",
                "{\"username\":\"operator\",\"password\":[\"SyntheticPassword-123\"]}","{} {}","{\"username\":\""+"x".repeat(66000)+"\"}"})code(f.post("auth/login",body,null),10001);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_login_session",Long.class)).isZero();
    });}
    @Test void credentialsOriginsAndLegacyRoutesCannotBypassProductionFilter(){fixture("httpcredentials",false,f->{String token=f.operator();
        code(f.post("context/current","{}",null),20001);code(f.post("context/current","{}","A".repeat(43)),20001);
        code(f.post("context/current","{}",token,"Authorization","Bearer "+token),20001);
        code(f.post("context/current","{}",token,"X-User-Id","1"),70001);code(f.post("context/current","{}",token,"Origin","https://untrusted.invalid"),70001);
        code(f.post("auth/login","{}",token),70001);code(f.post("../../../login/localLogin","{}",token),70001);
        code(f.post("context/current?tenantId=20","{}",token),70001);code(f.post("context/current","{}",token,"X-EMBEDDED-TOKEN",token),70001);
        code(f.post("context/current","{}",token),0);
    });}
    @Test void realContextSwitchChecksMemberCasAndImmediateRevocation(){fixture("httpcontext",false,f->{f.member();String token=f.login("member","SyntheticPassword-123");
        code(f.post("context/switch","{\"tenantId\":\"20\",\"expectedVersion\":\"1\"}",token),70001);
        var selected=f.post("context/switch","{\"tenantId\":\"10\",\"expectedVersion\":\"1\"}",token);code(selected,0);assertThat(selected.path("data").path("version").asText()).isEqualTo("2");
        code(f.post("context/switch","{\"tenantId\":\"10\",\"expectedVersion\":\"1\"}",token),50002);
        code(f.post("context/switch","{\"tenantId\":10,\"expectedVersion\":\"2\"}",token),10001);
        f.jdbc.update("UPDATE de_ent_tenant_member SET status='DISABLED' WHERE id=100");code(f.post("context/current","{}",token),70001);
        f.jdbc.update("UPDATE de_ent_user SET identity_epoch=identity_epoch+1 WHERE id=2");code(f.post("context/current","{}",token),20001);
    });}
    @Test void realPasswordChangeAndExpiryInvalidateExistingCredentials(){fixture("httppassword",false,f->{
        String first=f.login("operator","SyntheticPassword-123"),second=f.login("operator","SyntheticPassword-123");
        code(f.post("auth/password","{\"previousPassword\":\"SyntheticPassword-123\",\"newPassword\":\"ReplacementPassword-123\"}",first),0);
        code(f.post("context/current","{}",first),20001);code(f.post("context/current","{}",second),20001);
        String current=f.login("operator","ReplacementPassword-123");code(f.post("context/current","{}",current),0);
        f.jdbc.update("UPDATE de_ent_login_session SET created_at=UTC_TIMESTAMP(6)-INTERVAL 8 HOUR,last_seen_at=UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE,idle_expires_at=UTC_TIMESTAMP(6)-INTERVAL 2 SECOND,expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE status='ACTIVE'");
        code(f.post("context/current","{}",current),20001);
    });}
    @Test void realOrganizationMemberLifecycleHasAuditCasAndImmediateRevocation(){fixture("httpmembers",false,f->{
        String operator=f.operator();Group a=f.group(operator,"admina","A");String school=f.school(a,"A-SCHOOL");
        var parent=f.post("organizations/save","{\"mode\":\"CREATE\",\"kind\":\"DEPARTMENT\",\"name\":\"Parent\",\"status\":\"ACTIVE\"}",a.token());code(parent,0);String parentId=parent.path("data").path("id").asText();
        var child=f.post("organizations/save","{\"mode\":\"CREATE\",\"kind\":\"DEPARTMENT\",\"name\":\"Child\",\"parentId\":\""+parentId+"\",\"status\":\"ACTIVE\"}",a.token());code(child,0);String childId=child.path("data").path("id").asText();
        String update="{\"mode\":\"UPDATE\",\"id\":\""+childId+"\",\"expectedVersion\":\"1\",\"kind\":\"DEPARTMENT\",\"name\":\"Updated\",\"parentId\":null,\"status\":\"ACTIVE\"}";
        code(f.post("organizations/save",update,a.token()),0);code(f.post("organizations/save",update,a.token()),50002);
        var list=f.post("organizations/page","{}",a.token());code(list,0);assertThat(list.path("data").path("total").asInt()).isEqualTo(3);
        assertThat(f.jdbc.queryForObject("SELECT parent_id FROM de_ent_org WHERE id=?",Long.class,Long.parseLong(childId))).isNull();
        String user=f.user(operator,"participant");String create="{\"mode\":\"CREATE\",\"userId\":\""+user+"\",\"status\":\"ACTIVE\",\"organizationIds\":[\""+school+"\",\""+childId+"\"]}";
        var member=f.post("members/save",create,a.token());code(member,0);String memberId=member.path("data").path("id").asText();code(f.post("members/save",create,a.token()),50003);
        var members=f.post("members/page","{}",a.token());code(members,0);assertThat(members.path("data").path("total").asInt()).isEqualTo(2);
        String token=f.login("participant","SyntheticPassword-123");code(f.post("auth/password","{\"previousPassword\":\"SyntheticPassword-123\",\"newPassword\":\"ReplacementPassword-123\"}",token),0);
        token=f.login("participant","ReplacementPassword-123");code(f.post("context/switch","{\"tenantId\":\""+a.tenantId()+"\",\"expectedVersion\":\"1\"}",token),0);code(f.post("members/page","{}",token),70001);
        String disable="{\"mode\":\"UPDATE\",\"id\":\""+memberId+"\",\"expectedVersion\":\"1\",\"userId\":\""+user+"\",\"status\":\"DISABLED\",\"organizationIds\":[]}";
        code(f.post("members/save",disable,a.token()),0);code(f.post("members/save",disable,a.token()),50002);code(f.post("context/current","{}",token),70001);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_org_member WHERE member_id=?",Long.class,Long.parseLong(memberId))).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_audit_event WHERE event_type='MEMBER_UPDATED'",Long.class)).isEqualTo(1);
        code(f.post("organizations/school","{\"id\":\""+school+"\"}",a.token()),0);
    });}
    @Test void groupResourcesAndSchoolReferencesRejectBothDirectionsWithoutMetadata(){fixture("httpgroups",false,f->{
        String operator=f.operator();Group a=f.group(operator,"admina","A"),b=f.group(operator,"adminb","B");String sa=f.school(a,"A-SCHOOL"),sb=f.school(b,"B-SCHOOL");
        code(f.post("organizations/school","{\"id\":\""+sb+"\"}",a.token()),70002);code(f.post("organizations/school","{\"id\":\""+sa+"\"}",b.token()),70002);
        code(f.post("organizations/save","{\"mode\":\"CREATE\",\"kind\":\"DEPARTMENT\",\"name\":\"Foreign\",\"parentId\":\""+sb+"\",\"status\":\"ACTIVE\"}",a.token()),70002);
        code(f.post("members/save","{\"mode\":\"UPDATE\",\"id\":\""+b.memberId()+"\",\"expectedVersion\":\"1\",\"userId\":\""+b.userId()+"\",\"status\":\"DISABLED\",\"organizationIds\":[]}",a.token()),70002);
        code(f.post("organizations/school","{\"id\":\"999\"}",a.token()),70002);code(f.post("organizations/school","{\"id\":\"\"}",a.token()),10001);
        code(f.post("organizations/school","{\"id\":\""+sa+"\"}",a.token(),"X-Tenant-Id",b.tenantId()),70001);
        var page=f.post("members/page","{}",a.token());code(page,0);assertThat(page.path("data").path("records").get(0).path("userId").asText()).isEqualTo(a.userId());
    });}
    @Test void lastAdministratorAndImmutableSchoolAttributionCannotBeRemoved(){fixture("httpinvariant",false,f->{
        String operator=f.operator();Group a=f.group(operator,"admina","A");
        code(f.post("members/save","{\"mode\":\"UPDATE\",\"id\":\""+a.memberId()+"\",\"expectedVersion\":\"1\",\"userId\":\""+a.userId()+"\",\"status\":\"DISABLED\",\"organizationIds\":[]}",a.token()),70001);
        assertThat(f.jdbc.queryForObject("SELECT status FROM de_ent_tenant_member WHERE id=?",String.class,Long.parseLong(a.memberId()))).isEqualTo("ACTIVE");
        String school=f.school(a,"ORIGINAL");code(f.post("organizations/save","{\"mode\":\"UPDATE\",\"id\":\""+school+"\",\"expectedVersion\":\"1\",\"kind\":\"SCHOOL\",\"schoolCode\":\"CHANGED\",\"name\":\"School\",\"status\":\"ACTIVE\"}",a.token()),10001);
        long tenant=Long.parseLong(a.tenantId()),member=Long.parseLong(a.memberId()),org=Long.parseLong(school);
        f.jdbc.update("INSERT INTO de_ent_org_member(id,tenant_id,member_id,org_id,status) VALUES(900,?,?,?,'ACTIVE')",tenant,member,org);
        f.jdbc.update("INSERT INTO de_ent_subject(id,tenant_id,subject_type,org_id) VALUES(901,?,'ORG',?)",tenant,org);
        f.jdbc.update("UPDATE de_ent_admin_grant SET subject_id=901 WHERE tenant_id=?",tenant);
        code(f.post("organizations/save","{\"mode\":\"UPDATE\",\"id\":\""+school+"\",\"expectedVersion\":\"1\",\"kind\":\"SCHOOL\",\"schoolCode\":\"ORIGINAL\",\"name\":\"School\",\"status\":\"DISABLED\"}",a.token()),70001);
        assertThat(f.jdbc.queryForObject("SELECT status FROM de_ent_org WHERE id=?",String.class,org)).isEqualTo("ACTIVE");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_audit_event WHERE event_type IN ('MEMBER_UPDATED','ORGANIZATION_UPDATED')",Long.class)).isZero();
    });}
    @Test void reusedRealHttpWorkerClearsGroupContextAfterRejectedRequests(){fixture("httpthread",false,f->{
        String operator=f.operator();Group a=f.group(operator,"admina","A"),b=f.group(operator,"adminb","B");String sa=f.school(a,"A-SCHOOL"),sb=f.school(b,"B-SCHOOL");
        for(int i=0;i<5;i++){
            code(f.post("organizations/school","{\"id\":\""+sb+"\"}",a.token()),70002);
            var own=f.post("organizations/school","{\"id\":\""+sb+"\"}",b.token());code(own,0);assertThat(own.path("data").path("tenantId").asText()).isEqualTo(b.tenantId());
            code(f.post("organizations/school","{\"id\":0}",b.token()),10001);
            var back=f.post("organizations/school","{\"id\":\""+sa+"\"}",a.token());code(back,0);assertThat(back.path("data").path("tenantId").asText()).isEqualTo(a.tenantId());
        }
    });}
    @Test void nativeOwnershipRequiresSelectedGroupAndNeverGrantsEditing() { fixture("httpownership",false,f->{
        String root=f.operator();Group a=f.group(root,"admina","A"),b=f.group(root,"adminb","B");
        var ra=f.post("resources/create","{\"name\":\"A-private\"}",a.token());code(ra,0);String ia=ra.path("data").path("id").asText();
        var rb=f.post("resources/create","{\"name\":\"B-private\"}",b.token());code(rb,0);String ib=rb.path("data").path("id").asText();
        String readA="{\"id\":\""+ia+"\",\"action\":\"VIEW\"}",readB="{\"id\":\""+ib+"\",\"action\":\"VIEW\"}";
        code(f.post("resources/read",readB,a.token()),70002);code(f.post("resources/read",readA,b.token()),70002);code(f.post("resources/read",readA,a.token()),70001);
        code(f.post("context/switch","{\"tenantId\":\""+a.tenantId()+"\",\"expectedVersion\":\"1\"}",root),0);
        var own=f.post("resources/read",readA,root);code(own,0);assertThat(own.path("data").path("name").asText()).isEqualTo("A-private");code(f.post("resources/read",readB,root),70002);
        for(String action:new String[]{"EDIT","EXPORT","DRILL"})code(f.post("resources/read","{\"id\":\""+ia+"\",\"action\":\""+action+"\"}",root),70001);
        code(f.post("resources/create","{\"name\":\"Forbidden\"}",root),70001);
        code(f.post("context/switch","{\"tenantId\":\""+b.tenantId()+"\",\"expectedVersion\":\"2\"}",root),0);code(f.post("resources/read",readB,root),0);code(f.post("resources/read",readA,root),70002);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM data_visualization_info v JOIN de_ent_resource r ON v.id=r.id AND v.org_id=r.tenant_id",Long.class)).isEqualTo(2);
        f.jdbc.update("INSERT INTO data_visualization_info(id,name,org_id,node_type,type) VALUES(999,'Unregistered',?,'panel','dashboard')",Long.parseLong(b.tenantId()));
        code(f.post("resources/read","{\"id\":\"999\",\"action\":\"VIEW\"}",root),70002);
        code(f.post("resources/create","{\"name\":\"Rebind\",\"id\":\""+ia+"\",\"tenantId\":\""+b.tenantId()+"\"}",b.token()),10001);
    }); }
    @Test void platformQualificationsRemainIndependentAndRevocationIsImmediate() { fixture("httpplatform",false,f->{
        String root=f.operator();Group a=f.group(root,"admina","A");var resource=f.post("resources/create","{\"name\":\"A-private\"}",a.token());code(resource,0);String id=resource.path("data").path("id").asText();
        String readerId=f.user(root,"reader"),operatorId=f.user(root,"controller");
        f.jdbc.update("INSERT INTO de_ent_platform_qualification(id,user_id,qualification,status) VALUES(901,?,'GROUP_READ_ALL','ACTIVE'),(902,?,'PLATFORM_OPERATE','ACTIVE')",Long.parseLong(readerId),Long.parseLong(operatorId));
        for(String user:new String[]{"reader","controller"}){
            String token=f.login(user,"SyntheticPassword-123");code(f.post("auth/password","{\"previousPassword\":\"SyntheticPassword-123\",\"newPassword\":\"ReplacementPassword-123\"}",token),0);token=f.login(user,"ReplacementPassword-123");
            code(f.post("tenants/page","{}",token),0);code(f.post("context/switch","{\"tenantId\":\""+a.tenantId()+"\",\"expectedVersion\":\"1\"}",token),0);
            code(f.post("resources/read","{\"id\":\""+id+"\",\"action\":\"VIEW\"}",token),user.equals("reader")?0:70001);
            code(f.post("resources/create","{\"name\":\"No edit\"}",token),70001);
            if(user.equals("reader")){
                code(f.post("users/create","{\"username\":\"forbidden\",\"displayName\":\"Forbidden\",\"temporaryPassword\":\"SyntheticPassword-123\"}",token),70001);
                f.jdbc.update("UPDATE de_ent_platform_qualification SET status='DISABLED' WHERE id=901");code(f.post("resources/read","{\"id\":\""+id+"\",\"action\":\"VIEW\"}",token),70001);
            }
        }
    }); }
    @Test void nativeOwnershipFailureRollsBackResourceEpochAndAuditThenRetries() { fixture("httpresourceatomic",false,f->{
        String root=f.operator();Group a=f.group(root,"admina","A");long tenant=Long.parseLong(a.tenantId());
        long epoch=f.jdbc.queryForObject("SELECT access_epoch FROM de_ent_tenant WHERE id=?",Long.class,tenant),audits=f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_audit_event",Long.class);
        f.jdbc.execute("ALTER TABLE de_ent_resource ADD CONSTRAINT ck_w03_reject_fixture CHECK(id<0)");
        code(f.post("resources/create","{\"name\":\"Must rollback\"}",a.token()),40001);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM data_visualization_info",Long.class)).isZero();assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_resource",Long.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT access_epoch FROM de_ent_tenant WHERE id=?",Long.class,tenant)).isEqualTo(epoch);assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_audit_event",Long.class)).isEqualTo(audits);
        f.jdbc.execute("ALTER TABLE de_ent_resource DROP CHECK ck_w03_reject_fixture");code(f.post("resources/create","{\"name\":\"Retry succeeds\"}",a.token()),0);
    }); }

    private static String w04Body(Object fields) {
        try { return JSON.writeValueAsString(fields); } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static String w04Role(Fixture f, Group group, String name) {
        var response=f.post("roles/save",w04Body(java.util.Map.of("mode","CREATE","code",name,"name",name,"status","ACTIVE")),group.token());
        code(response,0);return response.path("data").path("id").asText();
    }
    private static Group w04Delegate(Fixture f, String operator, Group group, String name) {
        String user=f.user(operator,name);
        var member=f.post("members/save",w04Body(java.util.Map.of("mode","CREATE","userId",user,"status","ACTIVE","organizationIds",java.util.List.of())),group.token());
        code(member,0);
        String token=f.login(name,"SyntheticPassword-123");
        code(f.post("auth/password","{\"previousPassword\":\"SyntheticPassword-123\",\"newPassword\":\"ReplacementPassword-123\"}",token),0);
        token=f.login(name,"ReplacementPassword-123");
        code(f.post("context/switch",w04Body(java.util.Map.of("tenantId",group.tenantId(),"expectedVersion","1")),token),0);
        return new Group(user,member.path("data").path("id").asText(),group.tenantId(),token);
    }
    private static void w04Grant(Fixture f, Group group, String type, String target, String capability, String effect) {
        String column=switch(type){case "ORG"->"org_id";case "ROLE"->"role_id";case "USER"->"member_id";default->throw new AssertionError();};
        var subjects=f.jdbc.queryForList("SELECT id FROM de_ent_subject WHERE tenant_id=? AND "+column+"=?",Long.class,Long.parseLong(group.tenantId()),Long.parseLong(target));
        long subject;
        if(subjects.isEmpty()){
            subject=IDUtils.snowID();f.jdbc.update("INSERT INTO de_ent_subject(id,tenant_id,subject_type,"+column+") VALUES(?,?,?,?)",subject,Long.parseLong(group.tenantId()),type,Long.parseLong(target));
        }else subject=subjects.getFirst();
        f.jdbc.update("INSERT INTO de_ent_admin_grant(id,tenant_id,subject_id,capability,effect,status) VALUES(?,?,?,?,?,'ACTIVE')",IDUtils.snowID(),Long.parseLong(group.tenantId()),subject,capability,effect);
    }
    private static String w04State(Fixture f,Group group) {
        return f.jdbc.queryForObject("SELECT CONCAT(access_epoch,':',(SELECT COUNT(*) FROM de_ent_audit_event WHERE tenant_id=?),':',"
                +"(SELECT COUNT(*) FROM de_ent_role_assignment WHERE tenant_id=?),':',(SELECT COUNT(*) FROM de_ent_assignment_school WHERE tenant_id=?))"
                +" FROM de_ent_tenant WHERE id=?",String.class,Long.parseLong(group.tenantId()),Long.parseLong(group.tenantId()),Long.parseLong(group.tenantId()),Long.parseLong(group.tenantId()));
    }

    @Test void w04RoleLifecycleStrictCasAndBothGroupDirections(){fixture("httproles",false,f->{
        String root=f.operator();Group a=f.group(root,"admina","A"),b=f.group(root,"adminb","B");
        String role=w04Role(f,a,"rector"),foreign=w04Role(f,b,"finance");
        code(f.post("roles/save",w04Body(java.util.Map.of("mode","CREATE","code","rector","name","Duplicate","status","ACTIVE")),a.token()),50003);
        String before=w04State(f,a);
        code(f.post("roles/save",w04Body(java.util.Map.of("mode","UPDATE","id",foreign,"expectedVersion","1","code","finance","name","Bad","status","DISABLED")),a.token()),70002);
        code(f.post("roles/save",w04Body(java.util.Map.of("mode","UPDATE","id",role,"expectedVersion","1","code","rector","name","Bad","status","DISABLED")),b.token()),70002);
        assertThat(w04State(f,a)).isEqualTo(before);
        String update=w04Body(java.util.Map.of("mode","UPDATE","id",role,"expectedVersion","1","code","rector","name","Renamed","status","DISABLED"));
        code(f.post("roles/save",update,a.token()),0);code(f.post("roles/save",update,a.token()),50002);
        code(f.post("roles/save",w04Body(java.util.Map.of("mode","UPDATE","id",role,"expectedVersion","2","code","changed","name","Bad","status","ACTIVE")),a.token()),10001);
        code(f.post("roles/save","{\"mode\":\"CREATE\",\"id\":null,\"code\":\"bad\",\"name\":\"Bad\",\"status\":\"ACTIVE\"}",a.token()),10001);
        code(f.post("roles/save","{\"mode\":\"CREATE\",\"code\":\"bad\",\"code\":\"bad2\",\"name\":\"Bad\",\"status\":\"ACTIVE\"}",a.token()),10001);
        code(f.post("assignments/page","{\"memberId\":null}",a.token()),10001);
        code(f.post("roles/page","{}",null),20001);code(f.post("roles/page","{}",root),70001);
        var page=f.post("roles/page","{}",a.token());code(page,0);assertThat(page.path("data").path("total").asInt()).isEqualTo(1);
        assertThat(page.path("data").path("records").get(0).path("name").asText()).isEqualTo("Renamed");
        assertThat(f.jdbc.queryForObject("SELECT resource_type FROM de_ent_audit_event WHERE event_type='ROLE_UPDATED'",String.class)).isEqualTo("ROLE");
    });}

    @Test void w04AssignmentPairsReplaceClearRejectCrossGroupAndImmutableRoot(){fixture("httpassignments",false,f->{
        String root=f.operator();Group a=f.group(root,"admina","A"),b=f.group(root,"adminb","B");
        String first=f.school(a,"A1"),second=f.school(a,"A2"),foreign=f.school(b,"B1");
        String rector=w04Role(f,a,"rector"),finance=w04Role(f,a,"finance"),brole=w04Role(f,b,"brole");
        var body=new java.util.LinkedHashMap<String,Object>(java.util.Map.of("mode","CREATE","memberId",a.memberId(),"roleId",rector,"schoolIds",java.util.List.of(first),"status","ACTIVE"));
        var created=f.post("assignments/save",w04Body(body),a.token());code(created,0);String id=created.path("data").path("id").asText();
        code(f.post("assignments/save",w04Body(body),a.token()),50003);
        body.put("roleId",finance);body.put("schoolIds",java.util.List.of(second));code(f.post("assignments/save",w04Body(body),a.token()),0);
        var page=f.post("assignments/page",w04Body(java.util.Map.of("memberId",a.memberId())),a.token());code(page,0);
        var records=page.path("data").path("records");assertThat(records.size()).isEqualTo(2);
        for(var row:records)assertThat(row.path("schoolIds").get(0).asText()).isEqualTo(row.path("roleId").asText().equals(rector)?first:second);
        body.put("schoolIds",java.util.List.of(foreign));code(f.post("assignments/save",w04Body(body),a.token()),70002);
        body.put("schoolIds",java.util.List.of(first));body.put("roleId",brole);code(f.post("assignments/save",w04Body(body),a.token()),70002);
        body.put("roleId",finance);body.put("memberId",b.memberId());code(f.post("assignments/save",w04Body(body),a.token()),70002);
        body.put("memberId",a.memberId());body.put("schoolIds",java.util.List.of());code(f.post("assignments/save",w04Body(body),a.token()),10001);
        body.put("schoolIds",java.util.List.of(first,first));code(f.post("assignments/save",w04Body(body),a.token()),10001);
        code(f.post("assignments/page",w04Body(java.util.Map.of("schoolId",foreign)),a.token()),70002);
        body.put("mode","UPDATE");body.put("id",id);body.put("expectedVersion","1");body.put("roleId",rector);body.put("schoolIds",java.util.List.of(first,second));
        code(f.post("assignments/save",w04Body(body),a.token()),0);code(f.post("assignments/save",w04Body(body),a.token()),50002);
        body.put("expectedVersion","2");body.put("roleId",finance);code(f.post("assignments/save",w04Body(body),a.token()),10001);
        body.put("roleId",rector);body.put("status","DISABLED");body.put("schoolIds",java.util.List.of());code(f.post("assignments/save",w04Body(body),a.token()),0);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_assignment_school WHERE assignment_id=?",Long.class,Long.parseLong(id))).isZero();
        page=f.post("assignments/page",w04Body(java.util.Map.of("roleId",rector)),a.token());code(page,0);
        assertThat(page.path("data").path("records").get(0).path("schoolIds").size()).isZero();
    });}

    @Test void w04DelegatedMemberAndRoleMutationsCannotConferManagement(){fixture("httpdelegation",false,f->{
        String root=f.operator();Group a=f.group(root,"admina","A");String school=f.school(a,"A1");
        Group delegate=w04Delegate(f,root,a,"delegate");
        w04Grant(f,a,"USER",delegate.memberId(),"MANAGE_MEMBERS","ALLOW");w04Grant(f,a,"USER",delegate.memberId(),"MANAGE_ROLES","ALLOW");
        w04Grant(f,a,"ORG",school,"MANAGE_AUTHORIZATION","ALLOW");
        String before=w04State(f,a);
        code(f.post("members/save",w04Body(java.util.Map.of("mode","UPDATE","id",delegate.memberId(),"expectedVersion","1","userId",delegate.userId(),"status","ACTIVE","organizationIds",java.util.List.of(school))),delegate.token()),70001);
        assertThat(w04State(f,a)).isEqualTo(before);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_org_member WHERE member_id=?",Long.class,Long.parseLong(delegate.memberId()))).isZero();
        String ordinary=w04Role(f,delegate,"ordinary");
        code(f.post("assignments/save",w04Body(java.util.Map.of("mode","CREATE","memberId",delegate.memberId(),"roleId",ordinary,"schoolIds",java.util.List.of(school),"status","ACTIVE")),delegate.token()),0);
        String protectedRole=w04Role(f,a,"protected");w04Grant(f,a,"ROLE",protectedRole,"MANAGE_AUTHORIZATION","ALLOW");
        before=w04State(f,a);
        code(f.post("assignments/save",w04Body(java.util.Map.of("mode","CREATE","memberId",delegate.memberId(),"roleId",protectedRole,"schoolIds",java.util.List.of(school),"status","ACTIVE")),delegate.token()),70001);
        assertThat(w04State(f,a)).isEqualTo(before);
        code(f.post("members/page","{}",delegate.token()),0);code(f.post("roles/page","{}",delegate.token()),0);
    });}

    @Test void w04OrganizationDenyRemovalAndLastRoleAdministratorRollBack(){fixture("httpadminchanges",false,f->{
        String root=f.operator();Group a=f.group(root,"admina","A");String school=f.school(a,"A1");
        Group delegate=w04Delegate(f,root,a,"delegate");
        var org=f.post("organizations/save",w04Body(java.util.Map.of("mode","CREATE","kind","DEPARTMENT","name","Deny organization","status","ACTIVE")),a.token());code(org,0);String orgId=org.path("data").path("id").asText();
        code(f.post("members/save",w04Body(java.util.Map.of("mode","UPDATE","id",delegate.memberId(),"expectedVersion","1","userId",delegate.userId(),"status","ACTIVE","organizationIds",java.util.List.of(orgId))),a.token()),0);
        w04Grant(f,a,"USER",delegate.memberId(),"MANAGE_ORGANIZATIONS","ALLOW");w04Grant(f,a,"USER",delegate.memberId(),"MANAGE_AUTHORIZATION","ALLOW");w04Grant(f,a,"ORG",orgId,"MANAGE_AUTHORIZATION","DENY");
        String before=w04State(f,a);
        code(f.post("organizations/save",w04Body(java.util.Map.of("mode","UPDATE","id",orgId,"expectedVersion","1","kind","DEPARTMENT","name","Deny organization","status","DISABLED")),delegate.token()),70001);
        assertThat(w04State(f,a)).isEqualTo(before);
        String protectedRole=w04Role(f,a,"protected");
        for(String capability:java.util.List.of("MANAGE_ROLES","MANAGE_ORGANIZATIONS","MANAGE_AUTHORIZATION"))w04Grant(f,a,"ROLE",protectedRole,capability,"ALLOW");
        code(f.post("assignments/save",w04Body(java.util.Map.of("mode","CREATE","memberId",a.memberId(),"roleId",protectedRole,"schoolIds",java.util.List.of(school),"status","ACTIVE")),a.token()),0);
        f.jdbc.update("UPDATE de_ent_admin_grant g JOIN de_ent_subject s ON g.subject_id=s.id SET g.status='DISABLED' WHERE s.tenant_id=? AND s.member_id=?",Long.parseLong(a.tenantId()),Long.parseLong(a.memberId()));
        before=w04State(f,a);
        code(f.post("roles/save",w04Body(java.util.Map.of("mode","UPDATE","id",protectedRole,"expectedVersion","1","code","protected","name","Protected","status","DISABLED")),a.token()),70001);
        assertThat(w04State(f,a)).isEqualTo(before);
        code(f.post("organizations/save",w04Body(java.util.Map.of("mode","UPDATE","id",school,"expectedVersion","1","kind","SCHOOL","name","School","schoolCode","A1","status","DISABLED")),a.token()),70001);
        assertThat(w04State(f,a)).isEqualTo(before);
    });}

    private static java.util.Map<String,Object> pSubject(String type,String id) { return java.util.Map.of("type",type,"id",id); }
    private static java.util.Map<String,Object> pChange(String school) {
        return java.util.Map.of("operation","UPSERT","policyKind","DATA_ACCESS","resourceType","DATASET",
                "resourceScope",java.util.Map.of("kind","ALL_DATASETS_IN_TENANT"),"schoolScope",java.util.Map.of("kind","EXPLICIT","ids",java.util.List.of(school)),"action","VIEW","effect","ALLOW");
    }
    private static String pEpoch(Fixture f,Group g) { return f.jdbc.queryForObject("SELECT access_epoch FROM de_ent_tenant WHERE id=?",Long.class,Long.parseLong(g.tenantId())).toString(); }
    private static java.util.Map<String,Object> pBatch(Fixture f,Group g,Object subject,String key,Object...changes) {
        return java.util.Map.of("subject",subject,"expectedEpoch",pEpoch(f,g),"idempotencyKey",key,"changes",java.util.List.of(changes));
    }
    private static String pState(Fixture f,Group g) {
        return w04State(f,g)+":"+f.jdbc.queryForList("SELECT * FROM de_ent_grant WHERE tenant_id=? ORDER BY id",Long.parseLong(g.tenantId()))
                +":"+f.jdbc.queryForList("SELECT * FROM de_ent_grant_school WHERE tenant_id=? ORDER BY id",Long.parseLong(g.tenantId()))
                +":"+f.jdbc.queryForList("SELECT id,version,state,CAST(result_metadata AS CHAR) AS result_metadata FROM de_ent_idempotency WHERE tenant_id=? ORDER BY id",Long.parseLong(g.tenantId()))
                +":"+f.jdbc.queryForList("SELECT * FROM de_ent_subject WHERE tenant_id=? ORDER BY id",Long.parseLong(g.tenantId()))
                +":"+f.jdbc.queryForList("SELECT * FROM de_ent_admin_grant WHERE tenant_id=? ORDER BY id",Long.parseLong(g.tenantId()));
    }
    @Test void w04PermissionNestedJsonRejectsAmbiguityAndBounds(){fixture("httppermjson",false,f->{
        Group a=f.group(f.operator(),"admina","A");String school=f.school(a,"A1");
        String body=w04Body(pBatch(f,a,pSubject("USER",a.userId()),"permission-json-key",pChange(school)));
        for(String invalid:java.util.List.of(body.replace("\"type\":\"USER\"","\"type\":\"USER\",\"tenantId\":\"1\""),
                body.replace("\"type\":\"USER\"","\"type\":\"USER\",\"type\":\"ORG\""),body.replace("\"ids\":[\""+school+"\"]","\"ids\":null"),
                body.replace("\"action\":\"VIEW\"","\"action\":1"),body.replace("\"action\":\"VIEW\"","\"action\":null"),body+" {}")) {
            code(f.post("permissions/batch",invalid,a.token()),10001);
        }
        code(f.post("permissions/batch",body.replace("\"ids\":[\""+school+"\"]","\"ids\":[\""+school+"\",\""+school+"\"]"),a.token()),10001);
        code(f.post("permissions/batch",body.replace("permission-json-key","x"),a.token()),10001);
        code(f.post("permissions/batch","{\"padding\":\""+"x".repeat(65537)+"\"}",a.token()),10001);
        try {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+f.port()+"/de2api/api/enterprise/v1/permissions/batch"))
                    .header("Content-Type","application/json").header("Authorization","Bearer "+a.token())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(java.nio.charset.StandardCharsets.UTF_16))).build();
            code(JSON.readTree(HTTP.send(request,HttpResponse.BodyHandlers.ofString()).body()),10001);
        }catch(Exception failure){throw new AssertionError("UTF8 request boundary verification failed",failure);}
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_grant",Long.class)).isZero();
    });}
    @Test void w04PermissionThreeSubjectsReadWithoutWritingAndCreateOrganizationAtomically(){fixture("httppermsubject",false,f->{
        Group a=f.group(f.operator(),"admina","A");String school=f.school(a,"A1"),role=w04Role(f,a,"rector");
        long subjects=f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_subject",Long.class);
        code(f.post("permissions/rules/page",w04Body(java.util.Map.of("subject",pSubject("ORG",school))),a.token()),0);
        code(f.post("admin-capabilities/page",w04Body(java.util.Map.of("subject",pSubject("ORG",school))),a.token()),0);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_subject",Long.class)).isEqualTo(subjects);
        for(var subject:java.util.List.of(pSubject("ORG",school),pSubject("ROLE",role),pSubject("USER",a.userId()))) {
            code(f.post("permissions/batch",w04Body(pBatch(f,a,subject,"permission-subject-"+subject.get("type"),pChange(school))),a.token()),0);
            var page=f.post("permissions/rules/page",w04Body(java.util.Map.of("subject",subject)),a.token());code(page,0);
            assertThat(page.path("data").path("rules").path("records").get(0).path("status").asText()).isEqualTo("ACTIVE");
        }
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_subject",Long.class)).isEqualTo(subjects+1);
        var department=f.post("organizations/save",w04Body(java.util.Map.of("mode","CREATE","kind","DEPARTMENT","name","Linked department","schoolId",school,"status","ACTIVE")),a.token());code(department,0);
        code(f.post("organizations/save",w04Body(java.util.Map.of("mode","UPDATE","id",school,"expectedVersion","1","kind","SCHOOL","name","School","schoolCode","A1","status","DISABLED")),a.token()),0);
        var dynamic=new java.util.LinkedHashMap<String,Object>(pChange(school));dynamic.put("schoolScope",java.util.Map.of("kind","ALL_ACTIVE_IN_TENANT"));
        String before=pState(f,a);
        code(f.post("permissions/batch",w04Body(pBatch(f,a,pSubject("ORG",department.path("data").path("id").asText()),"permission-unavailable-org",dynamic)),a.token()),70002);
        assertThat(pState(f,a)).isEqualTo(before);
        var history=f.post("permissions/rules/page",w04Body(java.util.Map.of("subject",pSubject("ORG",school))),a.token());code(history,0);
        String grant=history.path("data").path("rules").path("records").get(0).path("id").asText();
        code(f.post("permissions/batch",w04Body(pBatch(f,a,pSubject("ORG",school),"permission-disabled-cleanup",java.util.Map.of("operation","DELETE","grantId",grant,"expectedVersion","1"))),a.token()),0);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_grant WHERE id=?",Long.class,Long.parseLong(grant))).isZero();
    });}
    @Test void w04PermissionResourceCatalogRequiresNativeOwnershipAndNeverReturnsData(){fixture("httppermcatalog",false,f->{
        Group a=f.group(f.operator(),"admina","A");String school=f.school(a,"A1");
        f.jdbc.update("INSERT INTO core_dataset_group(id,name,node_type) VALUES(991,'Dataset','dataset'),(992,'Folder','folder'),(993,'Unregistered','dataset')");
        f.jdbc.update("INSERT INTO de_ent_resource(id,tenant_id,resource_type,status) VALUES(991,?,'DATASET','ACTIVE'),(992,?,'DATASET','ACTIVE'),(994,?,'DATASET','ACTIVE')",Long.parseLong(a.tenantId()),Long.parseLong(a.tenantId()),Long.parseLong(a.tenantId()));
        var subject=pSubject("USER",a.userId());
        var cat=f.post("permissions/catalog",w04Body(java.util.Map.of("subject",subject,"resourceType","DATASET")),a.token());code(cat,0);
        assertThat(cat.path("data").path("resources").path("records").size()).isEqualTo(1);
        assertThat(cat.path("data").toString()).doesNotContain("union_sql","info","componentData");
        for(String id:java.util.List.of("991","992","993","994")) {
            var change=new java.util.LinkedHashMap<String,Object>(pChange(school));change.put("resourceScope",java.util.Map.of("kind","EXACT","id",id));
            code(f.post("permissions/batch",w04Body(pBatch(f,a,subject,"permission-dataset-"+id,change)),a.token()),id.equals("991")?0:70002);
        }
        code(f.post("permissions/catalog",w04Body(java.util.Map.of("subject",subject,"resourceType","DATASET","keyword","%_")),a.token()),0);
    });}
    @Test void w04PermissionBothGroupDirectionsAndMixedBatchAreRejected(){fixture("httppermcross",false,f->{
        String root=f.operator();Group a=f.group(root,"admina","A"),b=f.group(root,"adminb","B");String sa=f.school(a,"A1"),sb=f.school(b,"B1");
        for(var pair:java.util.List.of(java.util.List.of(a,b),java.util.List.of(b,a))) {
            Group own=pair.get(0),foreign=pair.get(1);String school=own==a?sa:sb,other=own==a?sb:sa;
            String before=pState(f,own);
            code(f.post("permissions/batch",w04Body(pBatch(f,own,pSubject("USER",foreign.userId()),"permission-foreign-user",pChange(school))),own.token()),70002);
            code(f.post("permissions/batch",w04Body(pBatch(f,own,pSubject("USER",own.userId()),"permission-foreign-school",pChange(other))),own.token()),70002);
            var second=new java.util.LinkedHashMap<String,Object>(pChange(other));second.put("effect","DENY");
            code(f.post("permissions/batch",w04Body(pBatch(f,own,pSubject("USER",own.userId()),"permission-mixed-school",pChange(school),second)),own.token()),70002);
            code(f.post("admin-capabilities/page",w04Body(java.util.Map.of("subject",pSubject("USER",foreign.userId()))),own.token()),70002);
            assertThat(pState(f,own)).isEqualTo(before);
        }
    });}
    @Test void w04PermissionDatabaseFailureRollsBackSubjectChildrenEpochAuditIdempotency(){fixture("httppermrollback",false,f->{
        Group a=f.group(f.operator(),"admina","A");String school=f.school(a,"A1");var body=pBatch(f,a,pSubject("ORG",school),"permission-atomic-retry",pChange(school));String before=pState(f,a);
        f.jdbc.execute("ALTER TABLE de_ent_grant_school ADD CONSTRAINT ck_w04_command_failure CHECK(id<0)");
        var failure=f.post("permissions/batch",w04Body(body),a.token());code(failure,40001);
        assertThat(failure.toString()).doesNotContain("ck_w04_command_failure","Hibernate","INSERT INTO");assertThat(pState(f,a)).isEqualTo(before);
        f.jdbc.execute("ALTER TABLE de_ent_grant_school DROP CHECK ck_w04_command_failure");
        code(f.post("permissions/batch",w04Body(body),a.token()),0);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_idempotency",Long.class)).isEqualTo(1);
    });}
    @Test void w04PermissionIdempotencyReplaysOldEpochRejectsChangedExpiredAndRevoked(){fixture("httppermreplay",false,f->{
        String root=f.operator();Group a=f.group(root,"admina","A");String school=f.school(a,"A1");
        Group delegate=w04Delegate(f,root,a,"delegate");w04Grant(f,a,"USER",delegate.memberId(),"MANAGE_AUTHORIZATION","ALLOW");
        var subject=pSubject("USER",delegate.userId());var body=pBatch(f,a,subject,"permission-replay-key",pChange(school));
        var first=f.post("permissions/batch",w04Body(body),delegate.token());code(first,0);String before=pState(f,a);
        var replay=f.post("permissions/batch",w04Body(body),delegate.token());code(replay,0);assertThat(replay.path("data").path("replayed").asBoolean()).isTrue();assertThat(pState(f,a)).isEqualTo(before);
        var changed=new java.util.LinkedHashMap<String,Object>(pChange(school));changed.put("effect","DENY");
        code(f.post("permissions/batch",w04Body(pBatch(f,a,subject,"permission-replay-key",changed)),delegate.token()),50002);
        code(f.post("permissions/batch",w04Body(pBatch(f,a,subject,"permission-second-key",changed)),delegate.token()),0);
        replay=f.post("permissions/batch",w04Body(body),delegate.token());code(replay,0);
        assertThat(replay.path("data").path("committedEpoch").asText()).isEqualTo(first.path("data").path("committedEpoch").asText());
        assertThat(replay.path("data").path("currentEpoch").asText()).isEqualTo(pEpoch(f,a));
        f.jdbc.update("UPDATE de_ent_idempotency SET created_at=UTC_TIMESTAMP(6)-INTERVAL 2 DAY,updated_at=UTC_TIMESTAMP(6)-INTERVAL 2 DAY,expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE idempotency_key='permission-replay-key'");
        code(f.post("permissions/batch",w04Body(body),delegate.token()),50002);
        f.jdbc.update("UPDATE de_ent_admin_grant g JOIN de_ent_subject s ON s.id=g.subject_id SET g.status='DISABLED' WHERE s.member_id=?",Long.parseLong(delegate.memberId()));
        before=pState(f,a);code(f.post("permissions/batch",w04Body(body),delegate.token()),70001);assertThat(pState(f,a)).isEqualTo(before);
    });}
    @Test void w04PermissionCasFullReplacementAndDeleteCreateNaturalKey(){fixture("httppermcas",false,f->{
        Group a=f.group(f.operator(),"admina","A");String one=f.school(a,"A1"),two=f.school(a,"A2");var subject=pSubject("USER",a.userId());
        var first=f.post("permissions/batch",w04Body(pBatch(f,a,subject,"permission-first-key",pChange(one))),a.token());code(first,0);String id=first.path("data").path("results").get(0).path("grantId").asText();
        var change=new java.util.LinkedHashMap<String,Object>(pChange(two));change.put("grantId",id);change.put("expectedVersion","1");
        code(f.post("permissions/batch",w04Body(pBatch(f,a,subject,"permission-update-key",change)),a.token()),0);
        assertThat(f.jdbc.queryForList("SELECT school_id FROM de_ent_grant_school WHERE grant_id=?",Long.class,Long.parseLong(id))).containsExactly(Long.parseLong(two));
        code(f.post("permissions/batch",w04Body(pBatch(f,a,subject,"permission-stale-version",change)),a.token()),50002);
        change.put("expectedVersion","2");change.put("schoolScope",java.util.Map.of("kind","ALL_ACTIVE_IN_TENANT"));
        code(f.post("permissions/batch",w04Body(pBatch(f,a,subject,"permission-change-key",change)),a.token()),10001);
        code(f.post("permissions/batch",w04Body(pBatch(f,a,subject,"permission-duplicate-key",pChange(one))),a.token()),50003);
        var deleted=java.util.Map.of("operation","DELETE","grantId",id,"expectedVersion","2");
        code(f.post("permissions/batch",w04Body(pBatch(f,a,subject,"permission-replace-key",deleted,pChange(one))),a.token()),0);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_grant WHERE id=?",Long.class,Long.parseLong(id))).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_grant_school",Long.class)).isEqualTo(1);
    });}
    @Test void w04PermissionSchoolsPaginationBindsEpochAndRuleRevision(){fixture("httppermpages",false,f->{
        Group a=f.group(f.operator(),"admina","A");var ids=new java.util.ArrayList<String>();
        for(int i=0;i<101;i++){long id=4000+i;f.jdbc.update("INSERT INTO de_ent_org(id,tenant_id,kind,name,school_code,status) VALUES(?,?,'SCHOOL','School',?,'ACTIVE')",id,Long.parseLong(a.tenantId()),"P"+i);ids.add(Long.toString(id));}
        var subject=pSubject("USER",a.userId());var change=new java.util.LinkedHashMap<String,Object>(pChange(ids.getFirst()));change.put("schoolScope",java.util.Map.of("kind","EXPLICIT","ids",ids));
        var saved=f.post("permissions/batch",w04Body(pBatch(f,a,subject,"permission-page-key",change)),a.token());code(saved,0);String grant=saved.path("data").path("results").get(0).path("grantId").asText();
        var page=f.post("permissions/rules/page",w04Body(java.util.Map.of("subject",subject)),a.token());code(page,0);var scope=page.path("data").path("rules").path("records").get(0).path("schoolScope");
        assertThat(scope.path("ids").size()).isEqualTo(100);assertThat(scope.path("total").asInt()).isEqualTo(101);assertThat(scope.path("complete").asBoolean()).isFalse();
        var body=new java.util.LinkedHashMap<String,Object>(java.util.Map.of("subject",subject,"grantId",grant,"expectedVersion","1","expectedEpoch",pEpoch(f,a),"schoolsPageNum",2,"schoolsPageSize",100));
        page=f.post("permissions/rules/page",w04Body(body),a.token());code(page,0);assertThat(page.path("data").path("schools").path("records").size()).isEqualTo(1);assertThat(page.path("data").path("complete").asBoolean()).isFalse();
        body.put("expectedVersion","2");code(f.post("permissions/rules/page",w04Body(body),a.token()),50002);body.put("expectedVersion","1");body.put("expectedEpoch","1");code(f.post("permissions/rules/page",w04Body(body),a.token()),50002);
        code(f.post("permissions/rules/page",w04Body(java.util.Map.of("subject",subject,"pageNum",2)),a.token()),10001);
    });}
    @Test void w04CapabilityBatchProtectsLastAdministratorAndSeparatesManagementFromData(){fixture("httpcapbatch",false,f->{
        String root=f.operator();Group a=f.group(root,"admina","A");Group delegate=w04Delegate(f,root,a,"delegate");
        var subject=pSubject("USER",a.userId());var grants=f.post("admin-capabilities/page",w04Body(java.util.Map.of("subject",subject)),a.token());code(grants,0);
        String id="";for(var g:grants.path("data").path("grants").path("records"))if(g.path("capability").asText().equals("MANAGE_AUTHORIZATION"))id=g.path("id").asText();assertThat(id).isNotEmpty();
        String before=pState(f,a);
        code(f.post("admin-capabilities/batch",w04Body(pBatch(f,a,subject,"capability-last-admin",java.util.Map.of("operation","DELETE","grantId",id,"expectedVersion","1"))),a.token()),70001);assertThat(pState(f,a)).isEqualTo(before);
        var grant=java.util.Map.of("operation","UPSERT","capability","MANAGE_AUTHORIZATION","effect","ALLOW","status","ACTIVE");
        code(f.post("admin-capabilities/batch",w04Body(pBatch(f,a,pSubject("USER",delegate.userId()),"capability-new-admin",grant)),a.token()),0);
        code(f.post("permissions/rules/page",w04Body(java.util.Map.of("subject",pSubject("USER",delegate.userId()))),delegate.token()),0);
        var resource=f.post("resources/create","{\"name\":\"Private\"}",a.token());code(resource,0);
        code(f.post("resources/read",w04Body(java.util.Map.of("id",resource.path("data").path("id").asText(),"action","VIEW")),delegate.token()),70001);
        code(f.post("admin-capabilities/batch",w04Body(pBatch(f,a,subject,"capability-remove-old",java.util.Map.of("operation","DELETE","grantId",id,"expectedVersion","1"))),delegate.token()),0);
        code(f.post("admin-capabilities/page",w04Body(java.util.Map.of("subject",subject)),a.token()),70001);
    });}
    @Test void w04PermissionConcurrentSameKeyCommitsOnceAndRetryRechecksAuthority(){fixture("httppermconcurrent",false,2,f->{
        Group a=f.group(f.operator(),"admina","A");String school=f.school(a,"A1");String body=w04Body(pBatch(f,a,pSubject("USER",a.userId()),"permission-concurrent-key",pChange(school)));
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);var one=workers.submit(()->{start.await();return f.post("permissions/batch",body,a.token());});var two=workers.submit(()->{start.await();return f.post("permissions/batch",body,a.token());});start.countDown();
            var r1=one.get(20,TimeUnit.SECONDS);var r2=two.get(20,TimeUnit.SECONDS);
            assertThat(r1.path("code").asInt()).isIn(0,70001);assertThat(r2.path("code").asInt()).isIn(0,70001);assertThat(r1.path("code").asInt()==0||r2.path("code").asInt()==0).isTrue();
        }catch(Exception failure){throw new AssertionError("Concurrent synthetic request failed",failure);}
        var retry=f.post("permissions/batch",body,a.token());code(retry,0);assertThat(retry.path("data").path("replayed").asBoolean()).isTrue();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_grant",Long.class)).isEqualTo(1);assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_grant_school",Long.class)).isEqualTo(1);assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_idempotency",Long.class)).isEqualTo(1);
    });}

    private static java.util.Map<String,Object> dRule(String type,String resource,String action,String effect,String scope,java.util.List<String> schools) {
        var sc=new java.util.LinkedHashMap<String,Object>();sc.put("kind",scope);if(schools!=null)sc.put("ids",schools);
        return java.util.Map.of("operation","UPSERT","policyKind",type.equals("DATASET")?"DATA_ACCESS":"RESOURCE_ACTION","resourceType",type,
                "resourceScope",resource==null?java.util.Map.of("kind","ALL_DATASETS_IN_TENANT"):java.util.Map.of("kind","EXACT","id",resource),"schoolScope",sc,"action",action,"effect",effect);
    }
    private static void dGrant(Fixture f,Group g,String type,String id,Object...rules) {code(f.post("permissions/batch",w04Body(pBatch(f,g,pSubject(type,id),"decision-"+java.util.UUID.randomUUID(),rules)),g.token()),0);}
    private static String dAssignment(Fixture f,Group g,Group target,String role,String...schools) {
        var r=f.post("assignments/save",w04Body(java.util.Map.of("mode","CREATE","memberId",target.memberId(),"roleId",role,"status","ACTIVE","schoolIds",java.util.List.of(schools))),g.token());code(r,0);return r.path("data").path("id").asText();
    }
    private static JsonNode dPreview(Fixture f,Group g,String target,String type,String resource,String action) {
        var r=f.post("permissions/preview",w04Body(java.util.Map.of("userId",target,"policyKind",type.equals("DATASET")?"DATA_ACCESS":"RESOURCE_ACTION","resourceType",type,"resourceId",resource,"action",action)),g.token());code(r,0);return r.path("data");
    }
    private static void dDatasets(Fixture f,Group a,Group b) {
        f.jdbc.update("INSERT INTO core_dataset_group(id,name,node_type) VALUES(991,'Finance','dataset'),(992,'Teaching','dataset'),(993,'Foreign','dataset'),(994,'Folder','folder')");
        f.jdbc.update("INSERT INTO de_ent_resource(id,tenant_id,resource_type,status) VALUES(991,?,'DATASET','ACTIVE'),(992,?,'DATASET','ACTIVE'),(993,?,'DATASET','ACTIVE'),(994,?,'DATASET','ACTIVE'),(995,?,'DATASET','ACTIVE')",Long.parseLong(a.tenantId()),Long.parseLong(a.tenantId()),Long.parseLong(b.tenantId()),Long.parseLong(a.tenantId()),Long.parseLong(a.tenantId()));
    }
    @Test void w04DecisionRoleSchoolPairsAndSourcesMatchCurrentNativeDataset() {fixture("httpdecisionpairs",false,f->{
        String root=f.operator();Group a=f.group(root,"admina","A"),b=f.group(root,"adminb","B"),u=w04Delegate(f,root,a,"mixed");
        String s1=f.school(a,"A1"),s2=f.school(a,"A2");dDatasets(f,a,b);
        String principal=w04Role(f,a,"principal"),finance=w04Role(f,a,"finance");String first=dAssignment(f,a,u,principal,s1),second=dAssignment(f,a,u,finance,s2);
        dGrant(f,a,"ROLE",principal,dRule("DATASET",null,"VIEW","ALLOW","ALL_ACTIVE_IN_TENANT",null));
        dGrant(f,a,"ROLE",finance,dRule("DATASET","991","VIEW","ALLOW","ASSIGNMENT",null));
        var p=dPreview(f,a,u.userId(),"DATASET","991","VIEW");assertThat(p.path("authorizationAllowed").asBoolean()).isTrue();assertThat(p.path("allowedSchoolIds").toString()).isEqualTo(w04Body(java.util.List.of(s1,s2)));
        assertThat(p.path("sources").toString()).contains(first,second);assertThat(p.path("executionReady").asBoolean()).isFalse();assertThat(p.path("pendingChecks").size()).isPositive();
        p=dPreview(f,a,u.userId(),"DATASET","992","VIEW");assertThat(p.path("allowedSchoolIds").toString()).isEqualTo(w04Body(java.util.List.of(s1)));assertThat(p.path("sources").toString()).doesNotContain(second);
        assertThat(p.path("identityEpoch").asText()).isEqualTo("2");assertThat(p.path("accessEpoch").asText()).isEqualTo(pEpoch(f,a));assertThat(p.path("resourceVersion").asText()).isEqualTo("1");
        code(f.post("permissions/preview",w04Body(java.util.Map.of("userId",u.userId(),"policyKind","DATA_ACCESS","resourceType","DATASET","resourceId","991","action","VIEW")),u.token()),70001);
        code(f.post("assignments/save",w04Body(java.util.Map.of("mode","UPDATE","id",first,"expectedVersion","1","memberId",u.memberId(),"roleId",principal,"status","ACTIVE","schoolIds",java.util.List.of(s1,s2))),a.token()),0);
        code(f.post("organizations/save",w04Body(java.util.Map.of("mode","UPDATE","id",s2,"expectedVersion","1","kind","SCHOOL","name","School","schoolCode","A2","status","DISABLED")),a.token()),0);
        assertThat(dPreview(f,a,u.userId(),"DATASET","991","VIEW").path("allowedSchoolIds").toString()).isEqualTo(w04Body(java.util.List.of(s1)));
        assertThat(dPreview(f,a,u.userId(),"DATASET","992","VIEW").path("allowedSchoolIds").toString()).isEqualTo(w04Body(java.util.List.of(s1)));

    });}
    @Test void w04DecisionOrganizationPersonalDenyAndOperationPrerequisites() {fixture("httpdecisionmerge",false,f->{
        String root=f.operator();Group a=f.group(root,"admina","A"),b=f.group(root,"adminb","B"),u=w04Delegate(f,root,a,"mixed");String s1=f.school(a,"A1"),s2=f.school(a,"A2");dDatasets(f,a,b);
        String role=w04Role(f,a,"principal");dAssignment(f,a,u,role,s1);dGrant(f,a,"ROLE",role,dRule("DATASET",null,"VIEW","ALLOW","ASSIGNMENT",null),dRule("DATASET",null,"EXPORT","ALLOW","ASSIGNMENT",null));
        String dept=f.post("organizations/save",w04Body(java.util.Map.of("mode","CREATE","kind","DEPARTMENT","name","Finance","schoolId",s2,"status","ACTIVE")),a.token()).path("data").path("id").asText();
        dGrant(f,a,"ORG",s2,dRule("DATASET",null,"VIEW","ALLOW","ALL_ACTIVE_IN_TENANT",null));
        code(f.post("members/save",w04Body(java.util.Map.of("mode","UPDATE","id",u.memberId(),"expectedVersion","1","userId",u.userId(),"status","ACTIVE","organizationIds",java.util.List.of(dept))),a.token()),0);
        assertThat(dPreview(f,a,u.userId(),"DATASET","991","VIEW").path("allowedSchoolIds").toString()).isEqualTo(w04Body(java.util.List.of(s1)));
        dGrant(f,a,"ORG",dept,dRule("DATASET","991","VIEW","ALLOW","EXPLICIT",java.util.List.of(s2)));
        dGrant(f,a,"USER",u.userId(),dRule("DATASET","991","EXPORT","DENY","EXPLICIT",java.util.List.of(s1)),dRule("DATASET","991","DRILL","ALLOW","ALL_ACTIVE_IN_TENANT",null));
        var p=dPreview(f,a,u.userId(),"DATASET","991","VIEW");assertThat(p.path("allowedSchoolIds").size()).isEqualTo(2);
        assertThat(dPreview(f,a,u.userId(),"DATASET","991","EXPORT").path("authorizationAllowed").asBoolean()).isFalse();assertThat(dPreview(f,a,u.userId(),"DATASET","991","DRILL").path("allowedSchoolIds").size()).isEqualTo(2);
        dGrant(f,a,"USER",u.userId(),dRule("DATASET","991","VIEW","DENY","EXPLICIT",java.util.List.of(s2)));
        assertThat(dPreview(f,a,u.userId(),"DATASET","991","DRILL").path("allowedSchoolIds").toString()).isEqualTo(w04Body(java.util.List.of(s1)));
    });}
    @Test void w04DecisionPreviewAndControlledResourceSharePolicyWithoutOpeningPayload() {fixture("httpdecisionresource",false,f->{
        String root=f.operator();Group a=f.group(root,"admina","A"),u=w04Delegate(f,root,a,"viewer");String id=f.post("resources/create","{\"name\":\"Empty\"}",a.token()).path("data").path("id").asText();
        String body=w04Body(java.util.Map.of("id",id,"action","VIEW"));code(f.post("resources/read",body,u.token()),70001);
        assertThat(dPreview(f,a,a.userId(),"DASHBOARD",id,"VIEW").path("authorizationAllowed").asBoolean()).isFalse();
        dGrant(f,a,"USER",u.userId(),dRule("DASHBOARD",id,"VIEW","ALLOW","NONE",null));var p=dPreview(f,a,u.userId(),"DASHBOARD",id,"VIEW");assertThat(p.path("authorizationAllowed").asBoolean()).isTrue();assertThat(p.path("allowedSchoolIds").size()).isZero();code(f.post("resources/read",body,u.token()),0);
        for(String action:java.util.List.of("EDIT","EXPORT","DRILL"))code(f.post("resources/read",w04Body(java.util.Map.of("id",id,"action",action)),u.token()),70001);
        f.jdbc.update("UPDATE data_visualization_info SET component_data='[{\"secret\":true}]' WHERE id=?",Long.parseLong(id));code(f.post("resources/read",body,u.token()),70002);
        f.jdbc.update("UPDATE data_visualization_info SET component_data='[]' WHERE id=?",Long.parseLong(id));
        dGrant(f,a,"USER",u.userId(),dRule("DASHBOARD",id,"VIEW","DENY","NONE",null));assertThat(dPreview(f,a,u.userId(),"DASHBOARD",id,"VIEW").path("authorizationAllowed").asBoolean()).isFalse();code(f.post("resources/read",body,u.token()),70001);
    });}
    @Test void w04DecisionPreviewRejectsForgedTargetsResourcesAndRevisionWithoutWriting() {fixture("httpdecisionboundary",false,f->{
        String root=f.operator();Group a=f.group(root,"admina","A"),b=f.group(root,"adminb","B");f.school(a,"A1");f.school(b,"B1");dDatasets(f,a,b);String before=pState(f,a);
        var body=new java.util.LinkedHashMap<String,Object>(java.util.Map.of("userId",a.userId(),"policyKind","DATA_ACCESS","resourceType","DATASET","resourceId","991","action","VIEW"));
        for(String id:java.util.List.of("993","994","995")){body.put("resourceId",id);code(f.post("permissions/preview",w04Body(body),a.token()),70002);}
        body.put("resourceId","991");body.put("userId",b.userId());code(f.post("permissions/preview",w04Body(body),a.token()),70002);
        body.put("userId",a.userId());body.put("expectedEpoch","1");code(f.post("permissions/preview",w04Body(body),a.token()),50002);body.remove("expectedEpoch");
        for(String field:java.util.List.of("tenantId","operatorUserId","schoolIds")){body.put(field,"1");code(f.post("permissions/preview",w04Body(body),a.token()),10001);body.remove(field);}
        body.put("userId",Long.parseLong(a.userId()));code(f.post("permissions/preview",w04Body(body),a.token()),10001);
        code(f.post("permissions/preview","{\"userId\":\"1\",\"userId\":\"2\"}",a.token()),10001);assertThat(pState(f,a)).isEqualTo(before);
        body.put("userId",b.userId());body.put("resourceId","991");code(f.post("permissions/preview",w04Body(body),b.token()),70002);
    });}
    @Test void w04DecisionPlatformViewQualificationIsExplicitAndOtherActionsRemainOrdinary() {fixture("httpdecisionplatform",false,f->{
        String root=f.operator();Group a=f.group(root,"admina","A"),b=f.group(root,"adminb","B");String s1=f.school(a,"A1"),s2=f.school(a,"A2");dDatasets(f,a,b);
        String operatorId=f.jdbc.queryForObject("SELECT id FROM de_ent_user WHERE username='operator'",Long.class).toString();
        var p=dPreview(f,a,operatorId,"DATASET","991","VIEW");assertThat(p.path("authorizationAllowed").asBoolean()).isTrue();assertThat(p.path("allowedSchoolIds").size()).isEqualTo(2);
        assertThat(dPreview(f,a,operatorId,"DATASET","991","EXPORT").path("authorizationAllowed").asBoolean()).isFalse();
        code(f.post("members/save",w04Body(java.util.Map.of("mode","CREATE","userId",operatorId,"status","ACTIVE","organizationIds",java.util.List.of())),a.token()),0);
        dGrant(f,a,"USER",operatorId,dRule("DATASET","991","VIEW","DENY","ALL_ACTIVE_IN_TENANT",null));assertThat(dPreview(f,a,operatorId,"DATASET","991","VIEW").path("authorizationAllowed").asBoolean()).isTrue();
        dGrant(f,a,"USER",operatorId,dRule("DATASET","991","EXPORT","ALLOW","EXPLICIT",java.util.List.of(s1)));
        assertThat(dPreview(f,a,operatorId,"DATASET","991","EXPORT").path("allowedSchoolIds").toString()).isEqualTo(w04Body(java.util.List.of(s1)));
        dGrant(f,a,"USER",operatorId,dRule("DATASET","991","EXPORT","DENY","EXPLICIT",java.util.List.of(s1)));
        assertThat(dPreview(f,a,operatorId,"DATASET","991","EXPORT").path("authorizationAllowed").asBoolean()).isFalse();
        f.jdbc.update("UPDATE de_ent_platform_qualification SET status='DISABLED' WHERE user_id="+operatorId+" AND qualification='GROUP_READ_ALL'");assertThat(dPreview(f,a,operatorId,"DATASET","991","VIEW").path("authorizationAllowed").asBoolean()).isFalse();
        var page=f.post("roles/page","{}",a.token());code(page,0);assertThat(page.path("data").path("total").asInt()).isZero();
    });}

    @Test void w04RevocationExplicitSchoolDependencyCannotRemoveManagementDeny() {fixture("httprevocationdependency",false,f->{
        String root=f.operator();Group a=f.group(root,"admina","A"),u=w04Delegate(f,root,a,"delegate");String school=f.school(a,"A1");
        var dept=f.post("organizations/save",w04Body(java.util.Map.of("mode","CREATE","kind","DEPARTMENT","name","Linked deny","schoolId",school,"status","ACTIVE")),a.token());code(dept,0);String id=dept.path("data").path("id").asText();
        code(f.post("members/save",w04Body(java.util.Map.of("mode","UPDATE","id",u.memberId(),"expectedVersion","1","userId",u.userId(),"status","ACTIVE","organizationIds",java.util.List.of(id))),a.token()),0);
        w04Grant(f,a,"USER",u.memberId(),"MANAGE_ORGANIZATIONS","ALLOW");w04Grant(f,a,"USER",u.memberId(),"MANAGE_AUTHORIZATION","ALLOW");w04Grant(f,a,"ORG",id,"MANAGE_AUTHORIZATION","DENY");
        code(f.post("admin-capabilities/page",w04Body(java.util.Map.of("subject",pSubject("USER",u.userId()))),u.token()),70001);
        String before=pState(f,a);String orgBefore=f.jdbc.queryForList("SELECT * FROM de_ent_org WHERE tenant_id=? ORDER BY id",Long.parseLong(a.tenantId())).toString();
        code(f.post("organizations/save",w04Body(java.util.Map.of("mode","UPDATE","id",school,"expectedVersion","1","kind","SCHOOL","name","School","schoolCode","A1","status","DISABLED")),u.token()),70001);
        assertThat(pState(f,a)).isEqualTo(before);assertThat(f.jdbc.queryForList("SELECT * FROM de_ent_org WHERE tenant_id=? ORDER BY id",Long.parseLong(a.tenantId())).toString()).isEqualTo(orgBefore);
        code(f.post("admin-capabilities/page",w04Body(java.util.Map.of("subject",pSubject("USER",u.userId()))),u.token()),70001);
        // An authorization administrator may deliberately make this change; the member is then reevaluated.
        code(f.post("organizations/save",w04Body(java.util.Map.of("mode","UPDATE","id",school,"expectedVersion","1","kind","SCHOOL","name","School","schoolCode","A1","status","DISABLED")),a.token()),0);
        code(f.post("admin-capabilities/page",w04Body(java.util.Map.of("subject",pSubject("USER",u.userId()))),u.token()),0);
    });}
    @Test void w04RevocationOldSessionsRecheckRulesRolesOrganizationsAndMembership() {fixture("httprevocations",false,f->{
        String root=f.operator();Group a=f.group(root,"admina","A"),b=f.group(root,"adminb","B"),u=w04Delegate(f,root,a,"viewer");String school=f.school(a,"A1");dDatasets(f,a,b);
        String dash=f.post("resources/create","{\"name\":\"Empty\"}",a.token()).path("data").path("id").asText();String role=w04Role(f,a,"reader");dAssignment(f,a,u,role,school);
        dGrant(f,a,"ROLE",role,dRule("DASHBOARD",dash,"VIEW","ALLOW","NONE",null));String view=w04Body(java.util.Map.of("id",dash,"action","VIEW"));code(f.post("resources/read",view,u.token()),0);
        code(f.post("roles/save",w04Body(java.util.Map.of("mode","UPDATE","id",role,"expectedVersion","1","code","reader","name","Reader","status","DISABLED")),a.token()),0);code(f.post("resources/read",view,u.token()),70001);
        dGrant(f,a,"ORG",school,dRule("DASHBOARD",dash,"VIEW","ALLOW","NONE",null));code(f.post("members/save",w04Body(java.util.Map.of("mode","UPDATE","id",u.memberId(),"expectedVersion","1","userId",u.userId(),"status","ACTIVE","organizationIds",java.util.List.of(school))),a.token()),0);code(f.post("resources/read",view,u.token()),0);
        code(f.post("members/save",w04Body(java.util.Map.of("mode","UPDATE","id",u.memberId(),"expectedVersion","2","userId",u.userId(),"status","ACTIVE","organizationIds",java.util.List.of())),a.token()),0);code(f.post("resources/read",view,u.token()),70001);
        dGrant(f,a,"USER",u.userId(),dRule("DASHBOARD",dash,"VIEW","ALLOW","NONE",null));code(f.post("resources/read",view,u.token()),0);
        var row=f.post("permissions/rules/page",w04Body(java.util.Map.of("subject",pSubject("USER",u.userId()))),a.token()).path("data").path("rules").path("records").get(0);
        code(f.post("permissions/batch",w04Body(pBatch(f,a,pSubject("USER",u.userId()),"revocation-delete-allow",java.util.Map.of("operation","DELETE","grantId",row.path("id").asText(),"expectedVersion",row.path("version").asText()))),a.token()),0);code(f.post("resources/read",view,u.token()),70001);
        dGrant(f,a,"USER",u.userId(),dRule("DASHBOARD",dash,"VIEW","ALLOW","NONE",null));code(f.post("resources/read",view,u.token()),0);
        code(f.post("members/save",w04Body(java.util.Map.of("mode","UPDATE","id",u.memberId(),"expectedVersion","3","userId",u.userId(),"status","DISABLED","organizationIds",java.util.List.of())),a.token()),0);code(f.post("resources/read",view,u.token()),70001);
        assertThat(dPreview(f,a,u.userId(),"DASHBOARD",dash,"VIEW").path("authorizationAllowed").asBoolean()).isFalse();
    });}
    @Test void w04RevocationFactReadSerializesWithPermissionWriteAndRejectsStaleEpoch() {fixture("httprevocationrace",false,2,f->{
        String root=f.operator();Group a=f.group(root,"admina","A"),b=f.group(root,"adminb","B"),u=w04Delegate(f,root,a,"viewer");String school=f.school(a,"A1");dDatasets(f,a,b);
        dGrant(f,a,"USER",u.userId(),dRule("DATASET","991","VIEW","ALLOW","EXPLICIT",java.util.List.of(school)));
        var sessions=f.context.getBean(io.dataease.enterprise.identity.manage.ManagementSessionService.class);var principal=sessions.authenticate(a.token());var access=sessions.access(principal);
        var transactions=f.context.getBean(io.dataease.enterprise.management.manage.ManagementTransactions.class);var loader=f.context.getBean(io.dataease.enterprise.permission.manage.PermissionFactLoader.class);var engine=f.context.getBean(io.dataease.enterprise.permission.domain.PermissionDecision.class);
        var locked=new CountDownLatch(1);var proceed=new CountDownLatch(1);String oldEpoch=pEpoch(f,a);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var read=executor.submit(()->{try(var scope=io.dataease.enterprise.context.AccessContextHolder.open(access)){return transactions.group(principal,null,false,(em,tenant)->{locked.countDown();try{if(!proceed.await(5,TimeUnit.SECONDS))throw new AssertionError("Read release timeout");}catch(InterruptedException ex){throw new AssertionError(ex);}return loader.load(em,tenant,Long.parseLong(u.userId()),"DATASET",991);});}});
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var body=w04Body(pBatch(f,a,pSubject("USER",u.userId()),"revocation-concurrent-deny",dRule("DATASET","991","VIEW","DENY","EXPLICIT",java.util.List.of(school))));
            var write=executor.submit(()->f.post("permissions/batch",body,a.token()));
            assertThatThrownBy(()->write.get(250,TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);proceed.countDown();var facts=read.get(10,TimeUnit.SECONDS);code(write.get(10,TimeUnit.SECONDS),0);
            assertThat(Long.toString(facts.accessEpoch())).isEqualTo(oldEpoch);assertThat(engine.evaluate(facts,"VIEW").authorizationAllowed()).isTrue();
            var current=dPreview(f,a,u.userId(),"DATASET","991","VIEW");assertThat(current.path("accessEpoch").asText()).isNotEqualTo(oldEpoch);assertThat(current.path("authorizationAllowed").asBoolean()).isFalse();
            try(var scope=io.dataease.enterprise.context.AccessContextHolder.open(access)){assertThatThrownBy(()->transactions.group(principal,null,false,(em,tenant)->loader.load(em,tenant,Long.parseLong(u.userId()),"DATASET",991))).isInstanceOf(io.dataease.exception.DEException.class);}
        } catch(Exception failure){throw new AssertionError("Permission revision concurrency failed",failure);}finally{proceed.countDown();}
    });}
    @Test void w04RevocationPreviewTargetsNeverReplaceWorkerIdentityOrOpenLegacyRoutes() {fixture("httprevocationworker",false,f->{
        String root=f.operator();Group a=f.group(root,"admina","A"),b=f.group(root,"adminb","B"),u=w04Delegate(f,root,a,"viewer");f.school(a,"A1");f.school(b,"B1");dDatasets(f,a,b);
        String ad=f.post("resources/create","{\"name\":\"A empty\"}",a.token()).path("data").path("id").asText(),bd=f.post("resources/create","{\"name\":\"B empty\"}",b.token()).path("data").path("id").asText();
        dPreview(f,a,u.userId(),"DATASET","991","VIEW");code(f.post("members/page","{}",a.token()),0);code(f.post("members/page","{}",u.token()),70001);
        for(var pair:java.util.List.of(java.util.List.of(a,bd),java.util.List.of(b,ad))){Group g=(Group)pair.get(0);String foreign=(String)pair.get(1);code(f.post("resources/read",w04Body(java.util.Map.of("id",foreign,"action","VIEW")),g.token()),70002);
            for(String route:java.util.List.of("chartData/getData","datasetData/previewData","visualization/save","visualization/findById","chartData/export","link/info","embedded/info","task/page"))code(f.post("/de2api/"+route,"{}",g.token()),70001);
            code(f.post("organizations/page","{}",g.token()),0);
        }
        assertThat(io.dataease.enterprise.context.AccessContextHolder.current()).isEmpty();
    });}

}
