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
    @Import({EnterpriseJpaConfiguration.class,FoundationConfiguration.class,ManagementConfiguration.class,IdentityManagementServer.class,GroupManagementServer.class,io.dataease.enterprise.management.server.ResourceOwnershipServer.class,io.dataease.enterprise.management.server.RoleManagementServer.class,
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
                    io.dataease.enterprise.management.persistence.EnterpriseRoleAssignment.class.getName(),io.dataease.enterprise.management.persistence.EnterpriseAssignmentSchool.class.getName(),io.dataease.enterprise.management.persistence.EnterpriseResource.class.getName(),io.dataease.dao.auto.entity.DataVisualizationInfo.class.getName());
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
    private void fixture(String scenario,boolean testBlocked,Consumer<Fixture> check){
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
                    "--server.tomcat.threads.max=1","--server.tomcat.threads.min-spare=1","--spring.jpa.hibernate.ddl-auto=update","--spring.jpa.open-in-view=true",
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
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/de2api/api/enterprise/v1/"+path)).timeout(Duration.ofSeconds(10))
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

    private static String w04Body(java.util.Map<String,Object> fields) {
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

}
