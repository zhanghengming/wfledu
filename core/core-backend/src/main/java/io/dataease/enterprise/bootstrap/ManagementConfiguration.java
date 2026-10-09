package io.dataease.enterprise.bootstrap;

import io.dataease.enterprise.identity.manage.ManagementSessionService;
import io.dataease.enterprise.identity.manage.PasswordCodec;
import io.dataease.enterprise.identity.manage.PlatformInitialization;
import io.dataease.enterprise.management.server.ManagementRequestFilter;
import io.dataease.enterprise.management.server.ManagementAccessContextResolver;
import io.dataease.enterprise.management.server.StrictManagementJson;
import io.dataease.enterprise.management.manage.*;
import io.dataease.enterprise.permission.manage.PermissionReadService;
import io.dataease.enterprise.permission.manage.PermissionBatchService;
import io.dataease.enterprise.permission.server.StrictPermissionJson;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Configuration(proxyBeanMethods=false)
public class ManagementConfiguration {
    @Bean static BeanFactoryPostProcessor managementSwitchGuard(Environment env){return factory->{
        String value=env.getProperty("enterprise.management.enabled","false");
        if(!Set.of("false","true").contains(value))throw new IllegalStateException("enterprise.management.enabled must be explicitly true or false");
        if(value.equals("true") && (!env.getProperty("enterprise.foundation.enabled","false").equals("true") || !env.getProperty("enterprise.enabled","false").equals("false")))
            throw new IllegalStateException("W03 management requires foundation=true and full enterprise=false");
    };}
    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(name="enterprise.management.enabled",havingValue="true")
    static class Enabled {
        @Bean ManagementSessionService managementSessionService(EntityManagerFactory factory,PlatformTransactionManager manager){return new ManagementSessionService(factory,requireJpa(manager,factory),Clock.systemUTC(),new PasswordCodec());}
        @Bean ManagementReadiness managementReadiness(){return new ManagementReadiness();}
        @Bean ManagementAccessContextResolver managementAccessContextResolver(ManagementSessionService sessions){return new ManagementAccessContextResolver(sessions);}
        @Bean ManagementAuthority managementAuthority(){return new ManagementAuthority();}
        @Bean GroupAdministrationInvariant groupAdministrationInvariant(ManagementAuthority authority){return new GroupAdministrationInvariant(authority);}
        @Bean ManagementTransactions managementTransactions(EntityManagerFactory factory,PlatformTransactionManager manager,ManagementSessionService sessions,ManagementAuthority authority){return new ManagementTransactions(factory,requireJpa(manager,factory),sessions,authority);}
        @Bean PlatformManagementService platformManagementService(ManagementTransactions transactions){return new PlatformManagementService(transactions,Clock.systemUTC());}
        @Bean io.dataease.enterprise.permission.domain.PermissionDecision permissionDecision(){return new io.dataease.enterprise.permission.domain.PermissionDecision();}
        @Bean io.dataease.enterprise.permission.manage.PermissionFactLoader permissionFactLoader(EntityManagerFactory factory){return new io.dataease.enterprise.permission.manage.PermissionFactLoader(factory);}
        @Bean io.dataease.enterprise.permission.manage.PermissionDecisionService permissionDecisionService(ManagementTransactions transactions,io.dataease.enterprise.permission.manage.PermissionFactLoader loader,io.dataease.enterprise.permission.domain.PermissionDecision decision){return new io.dataease.enterprise.permission.manage.PermissionDecisionService(transactions,loader,decision);}
        @Bean ResourceOwnershipService resourceOwnershipService(ManagementTransactions transactions,io.dataease.enterprise.permission.manage.PermissionDecisionService decisions){return new ResourceOwnershipService(transactions,decisions,Clock.systemUTC());}
        @Bean ManagementPrivilegeGuard managementPrivilegeGuard(ManagementAuthority authority){return new ManagementPrivilegeGuard(authority);}
        @Bean PermissionReadService permissionReadService(ManagementTransactions transactions){return new PermissionReadService(transactions);}
        @Bean PermissionBatchService permissionBatchService(ManagementTransactions transactions,GroupAdministrationInvariant invariant){return new PermissionBatchService(transactions,invariant,Clock.systemUTC());}
        @Bean RoleManagementService roleManagementService(ManagementTransactions transactions,GroupAdministrationInvariant invariant,ManagementPrivilegeGuard privileges){return new RoleManagementService(transactions,invariant,privileges,Clock.systemUTC());}
        @Bean MemberManagementService memberManagementService(ManagementTransactions transactions,GroupAdministrationInvariant invariant,ManagementPrivilegeGuard privileges){return new MemberManagementService(transactions,invariant,privileges,Clock.systemUTC());}
        @Bean OrganizationManagementService organizationManagementService(EntityManagerFactory factory,PlatformTransactionManager manager,ManagementSessionService sessions,ManagementAuthority authority,ManagementTransactions transactions,GroupAdministrationInvariant invariant){
            return new OrganizationManagementService(factory,requireJpa(manager,factory),sessions,authority,transactions,invariant);
        }
        @Bean @Order(3) ApplicationRunner managementInitialization(EntityManagerFactory factory,PlatformTransactionManager manager,Environment env){
            var jpa=requireJpa(manager,factory);return args->{
            String input=env.getProperty("enterprise.management.bootstrap-file");
            if(input!=null)PrivateInitializationInput.initialize(java.nio.file.Path.of(input),new PlatformInitialization(factory,jpa,new PasswordCodec(),Clock.systemUTC()));
            new TransactionTemplate(manager).executeWithoutResult(status->{
                var em=EntityManagerFactoryUtils.getTransactionalEntityManager(factory);
                if(em==null || em.createQuery("select count(q) from EnterprisePlatformQualification q,EnterpriseUser u,EnterpriseUserCredential c where q.userId=u.id and c.userId=u.id and u.status=:active and q.status=:active and q.qualification='PLATFORM_OPERATE'",Long.class)
                        .setParameter("active",io.dataease.enterprise.identity.persistence.FoundationStatus.ACTIVE).getSingleResult()<1)
                    throw new IllegalStateException("Explicit platform initialization required before management readiness");
                var hashes=em.createQuery("select c.encodedHash from EnterpriseUserCredential c,EnterpriseUser u,EnterprisePlatformQualification q where c.userId=u.id and q.userId=u.id and u.status=:active and q.status=:active and q.qualification='PLATFORM_OPERATE' and c.scheme='PBKDF2_SHA256'",String.class)
                        .setParameter("active",io.dataease.enterprise.identity.persistence.FoundationStatus.ACTIVE).getResultList();
                if(hashes.isEmpty())throw new IllegalStateException("Valid platform credential required before management readiness");
                hashes.forEach(PasswordCodec::verifyEncoding);
            });
        };}
        @Bean FilterRegistrationBean<ManagementRequestFilter> managementRequestFilter(ManagementSessionService sessions,ManagementReadiness ready,Environment env,ManagementAccessContextResolver resolver){
            Set<String> origins=new HashSet<>();String configured=env.getProperty("enterprise.management.allowed-origins","");
            if(!configured.isEmpty())for(String value:configured.split(",",-1)){
                var uri=java.net.URI.create(value);
                if(uri.getHost()==null || !List.of("http","https").contains(uri.getScheme()) || uri.getRawPath()!=null && !uri.getRawPath().isEmpty()
                        || uri.getRawQuery()!=null || uri.getFragment()!=null || uri.getUserInfo()!=null || !origins.add(value))throw new IllegalStateException("Invalid management origin allowlist");
            }
            var registration=new FilterRegistrationBean<>(new ManagementRequestFilter(sessions,ready,origins,resolver));
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);registration.setDispatcherTypes(DispatcherType.REQUEST,DispatcherType.FORWARD,DispatcherType.INCLUDE,DispatcherType.ERROR,DispatcherType.ASYNC);
            registration.setAsyncSupported(false);return registration;
        }
        @Bean WebMvcConfigurer managementJsonConverter(){return new WebMvcConfigurer(){
            @Override public void extendMessageConverters(List<org.springframework.http.converter.HttpMessageConverter<?>> converters){converters.addFirst(new StrictManagementJson());converters.addFirst(new StrictPermissionJson());}
        };}
        private static JpaTransactionManager requireJpa(PlatformTransactionManager manager,EntityManagerFactory factory){
            if(!(manager instanceof JpaTransactionManager jpa) || jpa.getEntityManagerFactory()!=factory)throw new IllegalStateException("Management requires the owning JPA transaction manager");
            return jpa;
        }
    }
}
