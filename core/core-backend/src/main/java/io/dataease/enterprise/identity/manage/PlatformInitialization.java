package io.dataease.enterprise.identity.manage;

import io.dataease.enterprise.audit.persistence.EnterpriseAuditEvent;
import io.dataease.enterprise.audit.persistence.EnterpriseAuditEvent.ManagementEvent;
import io.dataease.enterprise.identity.persistence.*;
import io.dataease.utils.IDUtils;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Set;

/** Explicit local initialization only; not a component, public API or default account supplier. */
public final class PlatformInitialization {
    private final EntityManagerFactory factory;
    private final TransactionTemplate transactions;
    private final PasswordCodec passwords;
    private final Clock clock;
    public PlatformInitialization(EntityManagerFactory factory,JpaTransactionManager manager,PasswordCodec passwords,Clock clock) {
        if(manager.getEntityManagerFactory()!=factory) throw new IllegalArgumentException("Initialization transaction factory mismatch");
        this.factory=factory;this.transactions=new TransactionTemplate(manager);transactions.setTimeout(10);this.passwords=passwords;this.clock=clock;
    }
    public long initialize(String username,String displayName,char[] password,Set<String> qualifications) {
        try {
            if(username==null || !username.matches("[A-Za-z0-9][A-Za-z0-9_.@-]{0,127}") || displayName==null
                    || displayName.isBlank() || displayName.codePointCount(0,displayName.length())>128 || !displayName.equals(displayName.strip())
                    || displayName.codePoints().anyMatch(Character::isISOControl) || qualifications==null || !qualifications.contains("PLATFORM_OPERATE")
                    || !Set.of("PLATFORM_OPERATE","GROUP_READ_ALL").containsAll(qualifications)) throw new IllegalArgumentException("Invalid platform initialization fields");
            if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Initialization owns its transaction");
            String encoded=passwords.encode(password);
            return transactions.execute(status->{
                var em=EntityManagerFactoryUtils.getTransactionalEntityManager(factory);if(em==null)throw new IllegalStateException("Missing initialization transaction");em.clear();
                // Serialize all initializations using the existing schema ledger, without a new mutable singleton table.
                var ledger=em.createNativeQuery("SELECT installed_rank FROM de_standalone_version WHERE version='4.4' AND success=1 FOR UPDATE").getResultList();
                if(ledger.size()!=1)throw new IllegalStateException("Successful formal credential migration required");
                for(String type:new String[]{"EnterpriseUser","EnterpriseUserCredential","EnterprisePlatformQualification","EnterpriseLoginSession","EnterpriseTenant"})
                    if(em.createQuery("select count(x) from "+type+" x",Long.class).getSingleResult()!=0)throw new IllegalStateException("Platform initialization requires empty identity state; existing accounts retained");
                var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
                long userId=IDUtils.snowID();var user=new EnterpriseUser();user.setId(userId);user.setUsername(username);user.setDisplayName(displayName);
                user.setStatus(FoundationStatus.ACTIVE);user.setCreatedAt(now);user.setUpdatedAt(now);em.persist(user);em.flush();
                var credential=new EnterpriseUserCredential();credential.setId(IDUtils.snowID());credential.setUserId(userId);credential.setEncodedHash(encoded);
                credential.setCreatedAt(now);credential.setUpdatedAt(now);credential.setPasswordChangedAt(now);em.persist(credential);
                for(String code:qualifications){var grant=new EnterprisePlatformQualification();grant.setId(IDUtils.snowID());grant.setUserId(userId);grant.setQualification(code);
                    grant.setStatus(FoundationStatus.ACTIVE);grant.setCreatedAt(now);grant.setUpdatedAt(now);em.persist(grant);}
                em.persist(EnterpriseAuditEvent.management(IDUtils.snowID(),now,null,null,ManagementEvent.PLATFORM_INITIALIZED,userId,null,1));
                em.flush();return userId;
            });
        } finally {if(password!=null)Arrays.fill(password,'\0');}
    }
}
