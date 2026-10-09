package io.dataease.enterprise.management.manage;

import io.dataease.enterprise.context.AccessContextHolder;
import io.dataease.enterprise.identity.manage.ManagementSessionService;
import io.dataease.enterprise.identity.manage.ManagementSessionService.Principal;
import io.dataease.enterprise.identity.persistence.*;
import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/** One short transaction for identity recheck, mutation, epoch and audit. */
public final class ManagementTransactions {
    private final EntityManagerFactory factory;
    private final TransactionTemplate transactions;
    private final ManagementSessionService sessions;
    private final ManagementAuthority authority;
    public ManagementTransactions(EntityManagerFactory factory,JpaTransactionManager manager,ManagementSessionService sessions,ManagementAuthority authority){
        if(manager.getEntityManagerFactory()!=factory)throw new IllegalArgumentException("Management transaction factory mismatch");
        this.factory=factory;this.transactions=new TransactionTemplate(manager);transactions.setTimeout(10);this.sessions=sessions;this.authority=authority;
    }
    public <T>T global(Principal principal,String qualification,Function<EntityManager,T> operation){return execute(em->{
        sessions.requireManagementPrincipal(em,principal);
        if(!ManagementSessionService.qualified(em,principal.userId(),qualification))throw denied();return operation.apply(em);
    });}
    public <T>T platformDiscovery(Principal principal,Function<EntityManager,T> operation){return execute(em->{
        sessions.requireManagementPrincipal(em,principal);
        if(!ManagementSessionService.qualified(em,principal.userId(),"PLATFORM_OPERATE") && !ManagementSessionService.qualified(em,principal.userId(),"GROUP_READ_ALL"))throw denied();
        return operation.apply(em);
    });}
    public <T>T group(Principal principal,String capability,boolean write,BiFunction<EntityManager,EnterpriseTenant,T> operation){
        var access=AccessContextHolder.requireCurrent();
        if(access.userId()!=principal.userId() || !Objects.equals(principal.tenantId(),access.tenantId()))throw denied();
        return execute(em->{
            sessions.requireManagementPrincipal(em,principal);
            var user=em.find(EnterpriseUser.class,access.userId(),LockModeType.PESSIMISTIC_READ);
            var tenant=em.find(EnterpriseTenant.class,access.tenantId());
            // Session recheck may already have loaded this tenant. Refresh under the lock before comparing its revision.
            if(tenant!=null)em.refresh(tenant,write?LockModeType.PESSIMISTIC_WRITE:LockModeType.PESSIMISTIC_READ);
            if(user==null || user.getStatus()!=FoundationStatus.ACTIVE || user.getIdentityEpoch()!=access.identityEpoch()
                    || tenant==null || tenant.getStatus()!=FoundationStatus.ACTIVE || tenant.getAccessEpoch()!=access.accessEpoch())throw denied();
            if(capability!=null)authority.require(em,access,capability);return operation.apply(em,tenant);
        });
    }
    private <T>T execute(Function<EntityManager,T> operation){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Management command owns its transaction");
        return transactions.execute(status->{var em=Objects.requireNonNull(EntityManagerFactoryUtils.getTransactionalEntityManager(factory));em.clear();return operation.apply(em);});
    }
    private static DEException denied(){return new DEException(ResultCode.PERMISSION_NO_ACCESS.code(),ResultCode.PERMISSION_NO_ACCESS.message());}
}
