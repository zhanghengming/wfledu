package io.dataease.enterprise.management.manage;

import io.dataease.enterprise.identity.manage.ManagementSessionService.Principal;
import io.dataease.enterprise.identity.manage.PasswordCodec;
import io.dataease.enterprise.identity.persistence.*;
import io.dataease.enterprise.management.persistence.EnterpriseSubject;
import io.dataease.enterprise.management.persistence.EnterpriseAdminGrant;
import io.dataease.enterprise.audit.persistence.EnterpriseAuditEvent;
import io.dataease.enterprise.audit.persistence.EnterpriseAuditEvent.ManagementEvent;
import io.dataease.enterprise.management.server.ManagementFields;
import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import io.dataease.result.PageResult;
import io.dataease.utils.IDUtils;
import org.springframework.data.domain.PageRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;

/** Platform metadata operations; these qualifications never grant business editing/export. */
public final class PlatformManagementService {
    private final ManagementTransactions transactions;
    private final Clock clock;
    public PlatformManagementService(ManagementTransactions transactions,Clock clock){this.transactions=transactions;this.clock=clock;}
    public PageResult<Map<String,Object>> page(Principal principal,int page,int size){return transactions.platformDiscovery(principal,em->{
        var records=em.createQuery("from EnterpriseTenant order by id",EnterpriseTenant.class).setFirstResult((page-1)*size).setMaxResults(size).getResultList().stream()
                .map(t->Map.<String,Object>of("id",t.getId().toString(),"version",t.getVersion().toString(),"code",t.getCode(),"name",t.getName(),"status",t.getStatus().name())).toList();
        return new PageResult<>(records,em.createQuery("select count(t) from EnterpriseTenant t",Long.class).getSingleResult(),PageRequest.of(page-1,size));
    });}
    public Map<String,String> createUser(Principal principal,String username,String display,char[] temporary){
        try{
            if(username==null || !username.matches("[A-Za-z0-9][A-Za-z0-9_.@-]{0,127}"))throw ManagementFields.invalid();ManagementFields.text(display,128);
            transactions.global(principal,"PLATFORM_OPERATE",em->true);
            String encoded;
            try{encoded=new PasswordCodec().encode(temporary);}catch(IllegalArgumentException invalid){throw ManagementFields.invalid();}
            return transactions.global(principal,"PLATFORM_OPERATE",em->{
                if(em.createQuery("select count(u) from EnterpriseUser u where username=:name",Long.class).setParameter("name",username).getSingleResult()!=0)throw duplicate();
                var now=now();long id=IDUtils.snowID();var user=record(new EnterpriseUser(),id,principal.userId(),now);user.setUsername(username);user.setDisplayName(display);user.setStatus(FoundationStatus.ACTIVE);em.persist(user);em.flush();
                var credential=record(new EnterpriseUserCredential(),IDUtils.snowID(),principal.userId(),now);credential.setUserId(id);credential.setEncodedHash(encoded);credential.setPasswordChangedAt(now);em.persist(credential);
                em.persist(EnterpriseAuditEvent.management(IDUtils.snowID(),now,null,principal.userId(),ManagementEvent.USER_CREATED,id,null,1));em.flush();
                return Map.of("id",Long.toString(id),"version","1");
            });
        }finally{if(temporary!=null)Arrays.fill(temporary,'\0');}
    }
    public Map<String,String> createTenant(Principal principal,String code,String name,long administrator){
        ManagementFields.text(code,64);ManagementFields.text(name,128);if(administrator<=0)throw ManagementFields.invalid();
        return transactions.global(principal,"PLATFORM_OPERATE",em->{
            if(em.createQuery("select count(t) from EnterpriseTenant t where code=:code",Long.class).setParameter("code",code).getSingleResult()!=0)throw duplicate();
            var admin=em.find(EnterpriseUser.class,administrator,jakarta.persistence.LockModeType.PESSIMISTIC_READ);
            if(admin==null || admin.getStatus()!=FoundationStatus.ACTIVE)throw new DEException(ResultCode.RESOURCE_NOT_EXIST.code(),ResultCode.RESOURCE_NOT_EXIST.message());
            var now=now();long id=IDUtils.snowID();var tenant=record(new EnterpriseTenant(),id,principal.userId(),now);tenant.setCode(code);tenant.setName(name);tenant.setStatus(FoundationStatus.ACTIVE);em.persist(tenant);em.flush();
            var member=record(new EnterpriseTenantMember(),IDUtils.snowID(),principal.userId(),now);member.setTenantId(id);member.setUserId(administrator);member.setStatus(FoundationStatus.ACTIVE);em.persist(member);em.flush();
            var subject=record(new EnterpriseSubject(),IDUtils.snowID(),principal.userId(),now);subject.setTenantId(id);subject.setSubjectType("USER");subject.setMemberId(member.getId());em.persist(subject);em.flush();
            for(String capability:ManagementAuthority.CAPABILITIES){var grant=record(new EnterpriseAdminGrant(),IDUtils.snowID(),principal.userId(),now);grant.setTenantId(id);grant.setSubjectId(subject.getId());grant.setCapability(capability);grant.setEffect("ALLOW");grant.setStatus(FoundationStatus.ACTIVE);em.persist(grant);}
            em.persist(EnterpriseAuditEvent.management(IDUtils.snowID(),now,id,principal.userId(),ManagementEvent.TENANT_CREATED,id,1L,1));em.flush();
            return Map.of("id",Long.toString(id),"version","1","administratorMemberId",member.getId().toString());
        });
    }
    static <T extends FoundationRecord>T record(T row,long id,long actor,LocalDateTime time){row.setId(id);row.setCreatedAt(time);row.setUpdatedAt(time);row.setCreatedBy(actor);row.setUpdatedBy(actor);return row;}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);}
    private static DEException duplicate(){return new DEException(ResultCode.DATA_ALREADY_EXISTED.code(),ResultCode.DATA_ALREADY_EXISTED.message());}
}
