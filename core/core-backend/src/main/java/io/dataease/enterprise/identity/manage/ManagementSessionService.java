package io.dataease.enterprise.identity.manage;

import io.dataease.enterprise.audit.persistence.EnterpriseAuditEvent;
import io.dataease.enterprise.audit.persistence.EnterpriseAuditEvent.ManagementEvent;
import io.dataease.enterprise.context.AccessContext;
import io.dataease.enterprise.identity.persistence.*;
import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import io.dataease.utils.IDUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Function;

/** Owns short metadata transactions; password derivation is outside database locks. */
public final class ManagementSessionService {
    public record Principal(long sessionId, long version, long userId, long identityEpoch, Long tenantId, boolean mustReset) { }
    public static final class LoginResult {
        private final String credential;
        private final Principal principal;
        LoginResult(String credential, Principal principal) { this.credential=credential; this.principal=principal; }
        public String credential() { return credential; }
        public Principal principal() { return principal; }
        @Override public String toString() { return "Management login result [redacted]"; }
    }
    private record Snapshot(long userId, long credentialId, long credentialVersion, String hash) { }
    private final EntityManagerFactory factory;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final PasswordCodec passwords;
    private final String dummy;
    private final SecureRandom random = new SecureRandom();

    public ManagementSessionService(EntityManagerFactory factory, JpaTransactionManager manager, Clock clock, PasswordCodec passwords) {
        this.factory=Objects.requireNonNull(factory); this.clock=Objects.requireNonNull(clock); this.passwords=Objects.requireNonNull(passwords);
        if (manager.getEntityManagerFactory()!=factory) throw new IllegalArgumentException("Identity transaction factory mismatch");
        this.transactions=new TransactionTemplate(manager); transactions.setTimeout(10);
        char[] value=new char[32]; Arrays.fill(value,'x'); this.dummy=passwords.encode(value); Arrays.fill(value,'\0');
    }

    public LoginResult login(String username, char[] password) {
        try {
            if (username==null || !username.matches("[A-Za-z0-9][A-Za-z0-9_.@-]{0,127}")) throw error(ResultCode.PARAM_IS_INVALID);
            validatePassword(password);
            Snapshot snapshot=tx(em->{
                var rows=em.createQuery("select u.id,c.id,c.version,c.encodedHash from EnterpriseUser u,EnterpriseUserCredential c where c.userId=u.id and u.username=:name",Object[].class)
                        .setParameter("name",username).getResultList();
                return rows.size()==1?new Snapshot((Long)rows.getFirst()[0],(Long)rows.getFirst()[1],(Long)rows.getFirst()[2],(String)rows.getFirst()[3]):null;
            });
            boolean matched=passwords.matches(password,snapshot==null?dummy:snapshot.hash());
            LoginResult result=tx(em->{
                if (snapshot==null) { audit(em,null,null,ManagementEvent.LOGIN_DENIED,null,null,1); return null; }
                var user=em.find(EnterpriseUser.class,snapshot.userId(),LockModeType.PESSIMISTIC_WRITE);
                var credential=em.find(EnterpriseUserCredential.class,snapshot.credentialId(),LockModeType.PESSIMISTIC_WRITE);
                var now=now();
                if (user==null || credential==null || user.getStatus()!=FoundationStatus.ACTIVE
                        || !credential.getEncodedHash().equals(snapshot.hash()) || !credential.getScheme().equals("PBKDF2_SHA256")) {
                    audit(em,null,snapshot.userId(),ManagementEvent.LOGIN_DENIED,null,null,1); return null;
                }
                if (credential.getLockedUntil()!=null && credential.getLockedUntil().isAfter(now)) {
                    audit(em,null,user.getId(),ManagementEvent.LOGIN_DENIED,null,null,1); return null;
                }
                if (credential.getLockedUntil()!=null) credential.setFailedAttempts(0);
                credential.setUpdatedAt(now); credential.setUpdatedBy(user.getId());
                if (!matched) {
                    int failures=Math.min(credential.getFailedAttempts(),4)+1; credential.setFailedAttempts(failures);
                    credential.setLockedUntil(failures>=5?now.plusMinutes(15):null);
                    audit(em,null,user.getId(),ManagementEvent.LOGIN_DENIED,null,null,1); return null;
                }
                credential.setFailedAttempts(0); credential.setLockedUntil(null);
                byte[] entropy=new byte[32]; random.nextBytes(entropy);
                String token=Base64.getUrlEncoder().withoutPadding().encodeToString(entropy); Arrays.fill(entropy,(byte)0);
                var session=new EnterpriseLoginSession(); session.setId(IDUtils.snowID()); session.setCreatedAt(now);
                session.setUserId(user.getId()); session.setIdentityEpoch(user.getIdentityEpoch()); session.setTokenHash(hash(token));
                session.setLastSeenAt(now); session.setExpiresAt(now.plusHours(8)); session.setIdleExpiresAt(now.plusMinutes(30));
                em.persist(session); em.flush();
                audit(em,null,user.getId(),ManagementEvent.LOGIN_SUCCEEDED,session.getId(),null,session.getVersion());
                return new LoginResult(token,principal(session,credential.isMustReset()));
            });
            if (result==null) throw error(ResultCode.USER_LOGIN_ERROR);
            return result;
        } finally { if(password!=null) Arrays.fill(password,'\0'); }
    }

    public Principal authenticate(String token) {
        byte[] digest=hash(token);
        return tx(em->{
            var sessions=em.createQuery("from EnterpriseLoginSession where tokenHash=:hash",EnterpriseLoginSession.class).setParameter("hash",digest).getResultList();
            if(sessions.size()!=1) throw error(ResultCode.USER_NOT_LOGGED_IN);
            var session=sessions.getFirst();
            var user=em.find(EnterpriseUser.class,session.getUserId());
            requireActive(em,session,user);
            var rows=em.createQuery("select c.mustReset from EnterpriseUserCredential c where c.userId=:user",Boolean.class).setParameter("user",user.getId()).getResultList();
            if(rows.size()!=1) throw error(ResultCode.USER_NOT_LOGGED_IN);
            var now=now();
            // CAS touch does not change the public context revision or overwrite a concurrent switch/close.
            var idle=now.plusMinutes(30); if(idle.isAfter(session.getExpiresAt())) idle=session.getExpiresAt();
            if(em.createQuery("update EnterpriseLoginSession set lastSeenAt=:now,idleExpiresAt=:idle where id=:id and version=:version and status='ACTIVE'")
                    .setParameter("now",now).setParameter("idle",idle).setParameter("id",session.getId()).setParameter("version",session.getVersion()).executeUpdate()!=1)
                throw error(ResultCode.USER_NOT_LOGGED_IN);
            return principal(session,rows.getFirst());
        });
    }

    public Principal switchTenant(Principal principal,long tenantId,long expectedVersion) {
        if(tenantId<=0 || expectedVersion<=0 || principal.mustReset()) throw error(ResultCode.PERMISSION_NO_ACCESS);
        return tx(em->{
            var user=em.find(EnterpriseUser.class,principal.userId(),LockModeType.PESSIMISTIC_READ);
            var session=em.find(EnterpriseLoginSession.class,principal.sessionId(),LockModeType.PESSIMISTIC_WRITE);
            requirePrincipal(session,principal); requireActive(em,session,user);
            if(session.getVersion()!=expectedVersion) throw error(ResultCode.DATA_IS_WRONG);
            requireTenant(em,user.getId(),tenantId);
            session.setSelectedTenantId(tenantId); em.flush();
            var tenant=em.find(EnterpriseTenant.class,tenantId);
            audit(em,tenantId,user.getId(),ManagementEvent.CONTEXT_SWITCHED,session.getId(),tenant.getAccessEpoch(),session.getVersion());
            return principal(session,false);
        });
    }

    public AccessContext access(Principal principal) {
        if(principal.mustReset() || principal.tenantId()==null) throw error(ResultCode.PERMISSION_NO_ACCESS);
        return tx(em->{
            var user=em.find(EnterpriseUser.class,principal.userId()); var session=em.find(EnterpriseLoginSession.class,principal.sessionId());
            requirePrincipal(session,principal); requireActive(em,session,user);
            var tenant=requireTenant(em,user.getId(),principal.tenantId());
            return new AccessContext(tenant.getId(),user.getId(),tenant.getAccessEpoch(),user.getIdentityEpoch());
        });
    }

    public void logout(Principal principal) {
        tx(em->{
            var user=em.find(EnterpriseUser.class,principal.userId(),LockModeType.PESSIMISTIC_READ);
            var session=em.find(EnterpriseLoginSession.class,principal.sessionId(),LockModeType.PESSIMISTIC_WRITE);
            requirePrincipal(session,principal); requireActive(em,session,user);
            session.setStatus("CLOSED"); em.flush(); audit(em,null,user.getId(),ManagementEvent.SESSION_CLOSED,session.getId(),null,session.getVersion()); return null;
        });
    }

    public void changePassword(Principal principal,char[] previous,char[] next) {
        try {
            validatePassword(previous); validatePassword(next);
            Snapshot snapshot=tx(em->{
                var rows=em.createQuery("from EnterpriseUserCredential where userId=:user",EnterpriseUserCredential.class).setParameter("user",principal.userId()).getResultList();
                if(rows.size()!=1) throw error(ResultCode.USER_NOT_LOGGED_IN);
                var c=rows.getFirst(); return new Snapshot(principal.userId(),c.getId(),c.getVersion(),c.getEncodedHash());
            });
            if(!passwords.matches(previous,snapshot.hash()) || Arrays.equals(previous,next)) throw error(ResultCode.USER_LOGIN_ERROR);
            String encoded=passwords.encode(next);
            tx(em->{
                var user=em.find(EnterpriseUser.class,principal.userId(),LockModeType.PESSIMISTIC_WRITE);
                var session=em.find(EnterpriseLoginSession.class,principal.sessionId(),LockModeType.PESSIMISTIC_WRITE);
                requirePrincipal(session,principal); requireActive(em,session,user);
                var credential=em.find(EnterpriseUserCredential.class,snapshot.credentialId(),LockModeType.PESSIMISTIC_WRITE);
                if(credential.getVersion()!=snapshot.credentialVersion() || user.getIdentityEpoch()==Long.MAX_VALUE) throw error(ResultCode.DATA_IS_WRONG);
                credential.setEncodedHash(encoded); credential.setMustReset(false); credential.setPasswordChangedAt(now());
                credential.setUpdatedAt(now()); credential.setUpdatedBy(user.getId()); credential.setLockedUntil(null); credential.setFailedAttempts(0);
                user.setIdentityEpoch(user.getIdentityEpoch()+1); user.setUpdatedAt(now()); user.setUpdatedBy(user.getId());
                em.flush();
                em.createQuery("update EnterpriseLoginSession set status='REVOKED',version=version+1 where userId=:user and status='ACTIVE'").setParameter("user",user.getId()).executeUpdate();
                audit(em,null,user.getId(),ManagementEvent.PASSWORD_CHANGED,user.getId(),null,user.getVersion()); return null;
            });
        } finally { if(previous!=null) Arrays.fill(previous,'\0'); if(next!=null) Arrays.fill(next,'\0'); }
    }

    public static boolean qualified(EntityManager em,long user,String qualification) {
        if(!qualification.equals("GROUP_READ_ALL") && !qualification.equals("PLATFORM_OPERATE")) return false;
        return em.createQuery("select count(q) from EnterprisePlatformQualification q,EnterpriseUser u where q.userId=u.id and u.id=:user and u.status=:active and q.status=:active and q.qualification=:qualification",Long.class)
                .setParameter("user",user).setParameter("active",FoundationStatus.ACTIVE).setParameter("qualification",qualification).getSingleResult()==1;
    }

    /** Recheck/lock the authenticated session in the metadata command's owning transaction. */
    public void requireManagementPrincipal(EntityManager em,Principal principal) {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || em==null || em!=EntityManagerFactoryUtils.getTransactionalEntityManager(factory))
            throw new IllegalStateException("Management session check requires the owning identity transaction");
        var user=em.find(EnterpriseUser.class,principal.userId(),LockModeType.PESSIMISTIC_READ);
        var session=em.find(EnterpriseLoginSession.class,principal.sessionId(),LockModeType.PESSIMISTIC_READ);
        requirePrincipal(session,principal);requireActive(em,session,user);
        var reset=em.createQuery("select c.mustReset from EnterpriseUserCredential c where c.userId=:user",Boolean.class).setParameter("user",user.getId()).getResultList();
        if(reset.size()!=1 || reset.getFirst())throw error(ResultCode.PERMISSION_NO_ACCESS);
    }

    private EnterpriseTenant requireTenant(EntityManager em,long user,long tenantId) {
        var tenant=em.find(EnterpriseTenant.class,tenantId);
        if(tenant==null || tenant.getStatus()!=FoundationStatus.ACTIVE) throw error(ResultCode.PERMISSION_NO_ACCESS);
        long members=em.createQuery("select count(m) from EnterpriseTenantMember m where m.tenantId=:tenant and m.userId=:user and m.status=:active",Long.class)
                .setParameter("tenant",tenantId).setParameter("user",user).setParameter("active",FoundationStatus.ACTIVE).getSingleResult();
        if(members!=1 && !qualified(em,user,"GROUP_READ_ALL") && !qualified(em,user,"PLATFORM_OPERATE")) throw error(ResultCode.PERMISSION_NO_ACCESS);
        return tenant;
    }

    private void requireActive(EntityManager em,EnterpriseLoginSession session,EnterpriseUser user) {
        var time=now();
        if(session==null || user==null || !"ACTIVE".equals(session.getStatus()) || user.getStatus()!=FoundationStatus.ACTIVE
                || !session.getIdentityEpoch().equals(user.getIdentityEpoch()) || !time.isBefore(session.getExpiresAt()) || !time.isBefore(session.getIdleExpiresAt()))
            throw error(ResultCode.USER_NOT_LOGGED_IN);
        if(session.getSelectedTenantId()!=null) requireTenant(em,user.getId(),session.getSelectedTenantId());
    }
    private static void requirePrincipal(EnterpriseLoginSession session,Principal principal) {
        if(session==null || session.getUserId()!=principal.userId() || session.getIdentityEpoch()!=principal.identityEpoch()
                || session.getVersion()!=principal.version() || !Objects.equals(session.getSelectedTenantId(),principal.tenantId())) throw error(ResultCode.USER_NOT_LOGGED_IN);
    }
    private static Principal principal(EnterpriseLoginSession s,boolean reset) { return new Principal(s.getId(),s.getVersion(),s.getUserId(),s.getIdentityEpoch(),s.getSelectedTenantId(),reset); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
    private <T>T tx(Function<EntityManager,T> operation) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Identity operation owns its transaction");
        return transactions.execute(status->{ var em=EntityManagerFactoryUtils.getTransactionalEntityManager(factory); if(em==null) throw new IllegalStateException("Missing identity transaction"); em.clear(); return operation.apply(em); });
    }
    private void audit(EntityManager em,Long tenant,Long actor,ManagementEvent event,Long resource,Long epoch,long version) {
        em.persist(EnterpriseAuditEvent.management(IDUtils.snowID(),now(),tenant,actor,event,resource,epoch,version));
    }
    private static void validatePassword(char[] value) { try { PasswordCodec.validate(value); } catch(IllegalArgumentException e) { throw error(ResultCode.PARAM_IS_INVALID); } }
    private static byte[] hash(String token) {
        try {
            if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw error(ResultCode.USER_NOT_LOGGED_IN);
            byte[] decoded=Base64.getUrlDecoder().decode(token);
            if(decoded.length!=32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token)) throw error(ResultCode.USER_NOT_LOGGED_IN);
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("Required SHA256 unavailable",e); }
    }
    private static DEException error(ResultCode code) { return new DEException(code.code(),code.message()); }
}
