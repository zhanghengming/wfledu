package io.dataease.enterprise.management.manage;

import io.dataease.enterprise.audit.manage.OrganizationAuditAppender;
import io.dataease.enterprise.context.AccessContext;
import io.dataease.enterprise.identity.manage.ManagementSessionService;
import io.dataease.enterprise.identity.manage.ManagementSessionService.Principal;
import io.dataease.enterprise.identity.persistence.EnterpriseOrganization;
import io.dataease.enterprise.tenant.manage.OrganizationTransactionKernel;
import io.dataease.result.PageResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.data.domain.PageRequest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/** Formal HTTP wiring of the existing organization kernel and its production audit port. */
public final class OrganizationManagementService {
    private final EntityManagerFactory factory;private final JpaTransactionManager manager;
    private final ManagementSessionService sessions;private final ManagementAuthority authority;
    private final ManagementTransactions transactions;private final GroupAdministrationInvariant invariant;
    public OrganizationManagementService(EntityManagerFactory factory,JpaTransactionManager manager,ManagementSessionService sessions,ManagementAuthority authority,ManagementTransactions transactions,GroupAdministrationInvariant invariant){
        this.factory=factory;this.manager=manager;this.sessions=sessions;this.authority=authority;this.transactions=transactions;this.invariant=invariant;
    }
    private OrganizationTransactionKernel kernel(Principal principal){return kernel(principal,null);}
    private OrganizationTransactionKernel kernel(Principal principal,OrganizationTransactionKernel.Update update){
        var privileges=new ManagementPrivilegeGuard(authority);
        var snapshot=new ManagementPrivilegeGuard.Snapshot[1];
        var actualAudit=new OrganizationAuditAppender(factory,()->java.util.UUID.randomUUID().toString());
        var checkedAuthority=new OrganizationTransactionKernel.Authority(){
            @Override public void requireManage(EntityManager em,AccessContext access){sessions.requireManagementPrincipal(em,principal);authority.requireManage(em,access);
                if(update!=null)snapshot[0]=privileges.capture(em,access.tenantId(),privileges.organizationUsers(em,access.tenantId(),update));}
            @Override public void requireMappingRead(EntityManager em,AccessContext access){sessions.requireManagementPrincipal(em,principal);
                if(!ManagementSessionService.qualified(em,access.userId(),"GROUP_READ_ALL"))authority.requireMappingRead(em,access);}
        };
        return new OrganizationTransactionKernel(factory,manager,checkedAuthority,(em,change)->{if(snapshot[0]!=null)privileges.verify(em,snapshot[0]);invariant.require(em,change.tenantId());actualAudit.append(em,change);},Clock.systemUTC(),128);
    }
    public OrganizationTransactionKernel.Mutation create(Principal p,OrganizationTransactionKernel.Create command){return kernel(p).create(command);}
    public OrganizationTransactionKernel.Mutation update(Principal p,OrganizationTransactionKernel.Update command,OrganizationTransactionKernel.Identity identity){return kernel(p,command).update(command,identity);}
    public OrganizationTransactionKernel.SchoolReference school(Principal p,long id){return kernel(p).resolveSchoolId(id);}
    public PageResult<Map<String,Object>> page(Principal p,int page,int size){return transactions.group(p,"MANAGE_ORGANIZATIONS",false,(em,tenant)->{
        var records=em.createQuery("from EnterpriseOrganization where tenantId=:tenant order by id",EnterpriseOrganization.class).setParameter("tenant",tenant.getId())
                .setFirstResult((page-1)*size).setMaxResults(size).getResultList().stream().map(o->{
                    Map<String,Object> result=new LinkedHashMap<>();result.put("id",o.getId().toString());result.put("version",o.getVersion().toString());result.put("kind",o.getKind().name());result.put("name",o.getName());result.put("status",o.getStatus().name());
                    result.put("schoolCode",o.getSchoolCode());result.put("schoolId",o.getSchoolId()==null?null:o.getSchoolId().toString());result.put("parentId",o.getParentId()==null?null:o.getParentId().toString());return result;
                }).toList();
        return new PageResult<>(records,em.createQuery("select count(o) from EnterpriseOrganization o where tenantId=:tenant",Long.class).setParameter("tenant",tenant.getId()).getSingleResult(),PageRequest.of(page-1,size));
    });}
}
