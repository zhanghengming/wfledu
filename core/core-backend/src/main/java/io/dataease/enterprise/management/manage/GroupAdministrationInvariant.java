package io.dataease.enterprise.management.manage;

import jakarta.persistence.EntityManager;
import io.dataease.enterprise.identity.persistence.FoundationStatus;
import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;

/** A group mutation may not remove its last effective authorization administrator. */
public final class GroupAdministrationInvariant {
    private final ManagementAuthority authority;
    public GroupAdministrationInvariant(ManagementAuthority authority){this.authority=authority;}
    public void require(EntityManager em,long tenant){
        em.flush();em.clear();
        int offset=0;
        while(true){
            var users=em.createQuery("select m.userId from EnterpriseTenantMember m,EnterpriseUser u where m.tenantId=:tenant and m.status=:active and u.id=m.userId and u.status=:active order by m.id",Long.class)
                    .setParameter("tenant",tenant).setParameter("active",FoundationStatus.ACTIVE).setFirstResult(offset).setMaxResults(100).getResultList();
            for(long user:users)if(authority.remainsAdministrator(em,tenant,user))return;
            if(users.size()<100)throw new DEException(ResultCode.PERMISSION_NO_ACCESS.code(),ResultCode.PERMISSION_NO_ACCESS.message());
            offset=Math.addExact(offset,100);
        }
    }
}
