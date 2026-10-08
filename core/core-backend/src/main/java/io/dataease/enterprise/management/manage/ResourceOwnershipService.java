package io.dataease.enterprise.management.manage;

import io.dataease.dao.auto.entity.DataVisualizationInfo;
import io.dataease.enterprise.audit.persistence.EnterpriseAuditEvent;
import io.dataease.enterprise.audit.persistence.EnterpriseAuditEvent.ManagementEvent;
import io.dataease.enterprise.identity.manage.ManagementSessionService;
import io.dataease.enterprise.identity.manage.ManagementSessionService.Principal;
import io.dataease.enterprise.identity.persistence.FoundationStatus;
import io.dataease.enterprise.management.persistence.EnterpriseResource;
import io.dataease.enterprise.management.server.ManagementFields;
import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import io.dataease.utils.IDUtils;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/** W03 exercises native ownership only. W04 supplies ordinary VIEW and W05 analysis queries. */
public final class ResourceOwnershipService {
    private final ManagementTransactions transactions;
    private final Clock clock;
    public ResourceOwnershipService(ManagementTransactions transactions, Clock clock) { this.transactions=transactions;this.clock=clock; }
    public Map<String,String> create(Principal principal,String name) {
        ManagementFields.text(name,128);
        return transactions.group(principal,"INSTANTIATE_TEMPLATES",true,(em,tenant)->{
            if(tenant.getAccessEpoch()==Long.MAX_VALUE)throw error(ResultCode.DATA_IS_WRONG);
            long id=IDUtils.snowID();
            if(em.find(DataVisualizationInfo.class,id)!=null || em.find(EnterpriseResource.class,id)!=null)throw error(ResultCode.DATA_ALREADY_EXISTED);
            var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
            var nativeResource=new DataVisualizationInfo();nativeResource.setId(id);nativeResource.setName(name);nativeResource.setPid(0L);
            nativeResource.setOrgId(tenant.getId());nativeResource.setLevel(0);nativeResource.setNodeType("panel");nativeResource.setType("dashboard");
            nativeResource.setCanvasStyleData("{}");nativeResource.setComponentData("[]");nativeResource.setMobileLayout(false);nativeResource.setStatus(0);
            nativeResource.setSelfWatermarkStatus(false);nativeResource.setSort(0);nativeResource.setCreateTime(clock.millis());nativeResource.setUpdateTime(clock.millis());
            nativeResource.setCreateBy(Long.toString(principal.userId()));nativeResource.setUpdateBy(Long.toString(principal.userId()));
            nativeResource.setSource("w03-managed-empty");nativeResource.setDeleteFlag(false);nativeResource.setVersion(3);nativeResource.setContentId("0");nativeResource.setCheckVersion("1");
            em.persist(nativeResource);em.flush();
            var owner=PlatformManagementService.record(new EnterpriseResource(),id,principal.userId(),now);owner.setTenantId(tenant.getId());owner.setResourceType("DASHBOARD");owner.setStatus(FoundationStatus.ACTIVE);em.persist(owner);
            long epoch=tenant.getAccessEpoch()+1;tenant.setAccessEpoch(epoch);tenant.setUpdatedAt(now);tenant.setUpdatedBy(principal.userId());
            em.persist(EnterpriseAuditEvent.management(IDUtils.snowID(),now,tenant.getId(),principal.userId(),ManagementEvent.RESOURCE_REGISTERED,id,epoch,1));em.flush();
            return Map.of("id",Long.toString(id),"version","1","accessEpoch",Long.toString(epoch));
        });
    }
    public Map<String,Object> read(Principal principal,long id,String action) {
        if(id<=0 || action==null || !java.util.Set.of("VIEW","EDIT","EXPORT","DRILL").contains(action))throw ManagementFields.invalid();
        return transactions.group(principal,null,false,(em,tenant)->{
            var owners=em.createQuery("from EnterpriseResource where tenantId=:tenant and id=:id and resourceType='DASHBOARD'",EnterpriseResource.class)
                    .setParameter("tenant",tenant.getId()).setParameter("id",id).getResultList();
            if(owners.size()!=1 || owners.getFirst().getStatus()!=FoundationStatus.ACTIVE)throw error(ResultCode.RESOURCE_NOT_EXIST);
            if(!action.equals("VIEW") || !ManagementSessionService.qualified(em,principal.userId(),"GROUP_READ_ALL"))throw error(ResultCode.PERMISSION_NO_ACCESS);
            var nativeResource=em.find(DataVisualizationInfo.class,id);
            if(nativeResource==null || Boolean.TRUE.equals(nativeResource.getDeleteFlag()) || !"panel".equals(nativeResource.getNodeType())
                    || !"dashboard".equals(nativeResource.getType()) || !tenant.getId().equals(nativeResource.getOrgId())
                    || !"METADATA_ONLY".equals(owners.getFirst().getPayloadPolicy()) || !"w03-managed-empty".equals(nativeResource.getSource())
                    || !"[]".equals(nativeResource.getComponentData()) || !"{}".equals(nativeResource.getCanvasStyleData()))throw error(ResultCode.RESOURCE_NOT_EXIST);
            return Map.of("id",nativeResource.getId().toString(),"tenantId",tenant.getId().toString(),"version",owners.getFirst().getVersion().toString(),
                    "name",nativeResource.getName(),"resourceType","DASHBOARD","resourceKind",owners.getFirst().getResourceKind(),"componentData",nativeResource.getComponentData(),"canvasStyleData",nativeResource.getCanvasStyleData());
        });
    }
    private static DEException error(ResultCode code){return new DEException(code.code(),code.message());}
}
