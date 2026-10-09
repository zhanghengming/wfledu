package io.dataease.enterprise.management.manage;

import io.dataease.enterprise.context.AccessContextHolder;
import io.dataease.enterprise.identity.manage.ManagementSessionService.Principal;
import io.dataease.enterprise.identity.persistence.*;
import io.dataease.enterprise.management.persistence.*;
import io.dataease.enterprise.audit.persistence.EnterpriseAuditEvent;
import io.dataease.enterprise.audit.persistence.EnterpriseAuditEvent.ManagementEvent;
import io.dataease.enterprise.management.server.ManagementFields;
import io.dataease.enterprise.tenant.domain.OrganizationHierarchy;
import io.dataease.enterprise.tenant.domain.OrganizationNode;
import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import io.dataease.result.PageResult;
import io.dataease.utils.IDUtils;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Membership aggregate: explicit organization set, immutable user/group, CAS, epoch and audit. */
public final class MemberManagementService {
    public record Save(boolean create,Long id,Long expectedVersion,long userId,FoundationStatus status,List<Long> organizationIds){ }
    private final ManagementTransactions transactions;
    private final GroupAdministrationInvariant invariant;
    private final Clock clock;
    private final ManagementPrivilegeGuard privileges;
    public MemberManagementService(ManagementTransactions transactions,GroupAdministrationInvariant invariant,ManagementPrivilegeGuard privileges,Clock clock){this.transactions=transactions;this.invariant=invariant;this.clock=clock;this.privileges=privileges;}
    public PageResult<Map<String,Object>> page(Principal principal,int page,int size){return transactions.group(principal,"MANAGE_MEMBERS",false,(em,tenant)->{
        var members=em.createQuery("select m,u.displayName from EnterpriseTenantMember m,EnterpriseUser u where m.tenantId=:tenant and u.id=m.userId order by m.id",Object[].class)
                .setParameter("tenant",tenant.getId()).setFirstResult((page-1)*size).setMaxResults(size).getResultList();
        var ids=members.stream().map(v->((EnterpriseTenantMember)v[0]).getId()).toList();Map<Long,List<String>> organizations=new HashMap<>();
        if(!ids.isEmpty())for(var row:em.createQuery("select o.memberId,o.orgId from EnterpriseOrgMember o where o.tenantId=:tenant and o.memberId in :members order by o.orgId",Object[].class)
                .setParameter("tenant",tenant.getId()).setParameter("members",ids).getResultList())organizations.computeIfAbsent((Long)row[0],ignored->new ArrayList<>()).add(row[1].toString());
        var records=members.stream().map(row->{var m=(EnterpriseTenantMember)row[0];return Map.<String,Object>of("id",m.getId().toString(),"version",m.getVersion().toString(),"userId",m.getUserId().toString(),"displayName",row[1],"status",m.getStatus().name(),"organizationIds",organizations.getOrDefault(m.getId(),List.of()));}).toList();
        return new PageResult<>(records,em.createQuery("select count(m) from EnterpriseTenantMember m where m.tenantId=:tenant",Long.class).setParameter("tenant",tenant.getId()).getSingleResult(),PageRequest.of(page-1,size));
    });}
    public Map<String,String> save(Principal principal,Save command){
        if(command==null || command.status()==null || command.userId()<=0 || command.organizationIds()==null || command.organizationIds().size()>500
                || new HashSet<>(command.organizationIds()).size()!=command.organizationIds().size() || command.organizationIds().stream().anyMatch(id->id==null || id<=0)
                || command.create() && (command.id()!=null || command.expectedVersion()!=null) || !command.create() && (command.id()==null || command.id()<=0 || command.expectedVersion()==null || command.expectedVersion()<=0))throw ManagementFields.invalid();
        return transactions.group(principal,"MANAGE_MEMBERS",true,(em,tenant)->{
            if(tenant.getAccessEpoch()==Long.MAX_VALUE || tenant.getVersion()==Long.MAX_VALUE)throw error(ResultCode.DATA_IS_WRONG);
            var target=em.find(EnterpriseUser.class,command.userId(),jakarta.persistence.LockModeType.PESSIMISTIC_READ);
            // Existing inactive identities remain revocable; activating membership still requires an active user.
            if(target==null || (command.create() || command.status()==FoundationStatus.ACTIVE) && target.getStatus()!=FoundationStatus.ACTIVE)throw error(ResultCode.RESOURCE_NOT_EXIST);
            for(long org:command.organizationIds())new OrganizationHierarchy(128).requireAvailable(node(em,tenant.getId(),org),id->findNode(em,tenant.getId(),id));
            var privilegeSnapshot=privileges.capture(em,tenant.getId(),List.of(command.userId()));
            long id,version;var now=now();
            if(command.create()){
                if(em.createQuery("select count(m) from EnterpriseTenantMember m where m.tenantId=:tenant and m.userId=:user",Long.class).setParameter("tenant",tenant.getId()).setParameter("user",command.userId()).getSingleResult()!=0)throw error(ResultCode.DATA_ALREADY_EXISTED);
                id=IDUtils.snowID();version=1;var member=PlatformManagementService.record(new EnterpriseTenantMember(),id,principal.userId(),now);
                member.setTenantId(tenant.getId());member.setUserId(command.userId());member.setStatus(command.status());em.persist(member);em.flush();
                var subject=PlatformManagementService.record(new EnterpriseSubject(),IDUtils.snowID(),principal.userId(),now);subject.setTenantId(tenant.getId());subject.setSubjectType("USER");subject.setMemberId(id);em.persist(subject);
            }else{
                id=command.id();var rows=em.createQuery("from EnterpriseTenantMember where tenantId=:tenant and id=:id",EnterpriseTenantMember.class).setParameter("tenant",tenant.getId()).setParameter("id",id).getResultList();
                if(rows.size()!=1)throw error(ResultCode.RESOURCE_NOT_EXIST);var previous=rows.getFirst();
                if(previous.getUserId()!=command.userId())throw ManagementFields.invalid();
                if(previous.getVersion()!=command.expectedVersion().longValue() || previous.getVersion()==Long.MAX_VALUE)throw error(ResultCode.DATA_IS_WRONG);
                version=previous.getVersion()+1;
                if(em.createQuery("update EnterpriseTenantMember set status=:status,version=version+1,updatedAt=:time,updatedBy=:actor where tenantId=:tenant and id=:id and version=:version")
                        .setParameter("status",command.status()).setParameter("time",now).setParameter("actor",principal.userId()).setParameter("tenant",tenant.getId()).setParameter("id",id).setParameter("version",command.expectedVersion()).executeUpdate()!=1)throw error(ResultCode.DATA_IS_WRONG);
                em.createQuery("delete from EnterpriseOrgMember where tenantId=:tenant and memberId=:member").setParameter("tenant",tenant.getId()).setParameter("member",id).executeUpdate();
            }
            for(long org:command.organizationIds()){
                var membership=PlatformManagementService.record(new EnterpriseOrgMember(),IDUtils.snowID(),principal.userId(),now);membership.setTenantId(tenant.getId());membership.setMemberId(id);membership.setOrgId(org);membership.setStatus(command.status());em.persist(membership);
            }
            long epoch=tenant.getAccessEpoch()+1;tenant.setAccessEpoch(epoch);tenant.setUpdatedAt(now);tenant.setUpdatedBy(principal.userId());
            privileges.verify(em,privilegeSnapshot);
            invariant.require(em,tenant.getId());
            em.persist(EnterpriseAuditEvent.management(IDUtils.snowID(),now,tenant.getId(),principal.userId(),command.create()?ManagementEvent.MEMBER_CREATED:ManagementEvent.MEMBER_UPDATED,id,epoch,version));em.flush();
            return Map.of("id",Long.toString(id),"version",Long.toString(version),"accessEpoch",Long.toString(epoch));
        });
    }
    static OrganizationNode node(EntityManager em,long tenant,long id){var node=findNode(em,tenant,id);if(node==null)throw error(ResultCode.RESOURCE_NOT_EXIST);return node;}
    static OrganizationNode findNode(EntityManager em,long tenant,long id){
        var rows=em.createQuery("from EnterpriseOrganization where tenantId=:tenant and id=:id",EnterpriseOrganization.class).setParameter("tenant",tenant).setParameter("id",id).getResultList();
        if(rows.size()!=1)return null;var o=rows.getFirst();return new OrganizationNode(o.getId(),o.getTenantId(),o.getParentId(),OrganizationNode.Kind.valueOf(o.getKind().name()),o.getName(),o.getSchoolCode(),o.getSchoolId(),OrganizationNode.Status.valueOf(o.getStatus().name()),o.getVersion());
    }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);}
    private static DEException error(ResultCode code){return new DEException(code.code(),code.message());}
}
