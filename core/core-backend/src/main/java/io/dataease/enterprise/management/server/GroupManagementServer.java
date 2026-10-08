package io.dataease.enterprise.management.server;

import io.dataease.api.permissions.enterprise.ManagementApi;
import io.dataease.api.permissions.enterprise.ManagementContract.*;
import io.dataease.enterprise.management.manage.PlatformManagementService;
import io.dataease.enterprise.management.manage.MemberManagementService;
import io.dataease.enterprise.management.manage.OrganizationManagementService;
import io.dataease.enterprise.identity.persistence.FoundationStatus;
import io.dataease.enterprise.tenant.domain.OrganizationNode;
import io.dataease.enterprise.tenant.manage.OrganizationTransactionKernel;
import io.dataease.result.ResultMessage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController @ConditionalOnProperty(name="enterprise.management.enabled",havingValue="true")
public class GroupManagementServer implements ManagementApi {
    private final PlatformManagementService platform;private final MemberManagementService members;private final OrganizationManagementService organizations;
    private final HttpServletRequest request;
    public GroupManagementServer(PlatformManagementService platform,MemberManagementService members,OrganizationManagementService organizations,HttpServletRequest request){this.platform=platform;this.members=members;this.organizations=organizations;this.request=request;}
    @Override public ResultMessage tenants(Page input){return ResultMessage.success(platform.page(IdentityManagementServer.principal(request),ManagementFields.page(input.pageNum()),ManagementFields.size(input.pageSize())));}
    @Override public ResultMessage createTenant(TenantCreate input){return ResultMessage.success(platform.createTenant(IdentityManagementServer.principal(request),input.code(),input.name(),ManagementFields.id(input.administratorUserId())));}
    @Override public ResultMessage createUser(UserCreate input){return ResultMessage.success(platform.createUser(IdentityManagementServer.principal(request),input.username(),input.displayName(),input.temporaryPassword()));}
    @Override public ResultMessage members(Page input){return ResultMessage.success(members.page(IdentityManagementServer.principal(request),ManagementFields.page(input.pageNum()),ManagementFields.size(input.pageSize())));}
    @Override public ResultMessage organizations(Page input){return ResultMessage.success(organizations.page(IdentityManagementServer.principal(request),ManagementFields.page(input.pageNum()),ManagementFields.size(input.pageSize())));}
    @Override public ResultMessage saveMember(MemberSave input){
        if(input.organizationIds()==null)throw ManagementFields.invalid();
        var status=status(input.status());boolean create=mode(input.mode());
        return ResultMessage.success(members.save(IdentityManagementServer.principal(request),new MemberManagementService.Save(create,ManagementFields.optionalId(input.id()),ManagementFields.optionalId(input.expectedVersion()),ManagementFields.id(input.userId()),status,input.organizationIds().stream().map(ManagementFields::id).toList())));
    }
    @Override public ResultMessage saveOrganization(OrganizationSave input){
        boolean create=mode(input.mode());OrganizationNode.Kind kind;
        try{kind=OrganizationNode.Kind.valueOf(input.kind());}catch(IllegalArgumentException|NullPointerException failure){throw ManagementFields.invalid();}
        var status=OrganizationNode.Status.valueOf(status(input.status()).name());ManagementFields.text(input.name(),128);
        if(kind==OrganizationNode.Kind.SCHOOL){ManagementFields.text(input.schoolCode(),64);if(input.schoolId()!=null)throw ManagementFields.invalid();}
        else if(input.schoolCode()!=null)throw ManagementFields.invalid();
        Long parent=ManagementFields.optionalId(input.parentId()),school=ManagementFields.optionalId(input.schoolId());
        OrganizationTransactionKernel.Mutation changed;
        if(create){if(input.id()!=null || input.expectedVersion()!=null)throw ManagementFields.invalid();
            changed=organizations.create(IdentityManagementServer.principal(request),new OrganizationTransactionKernel.Create(kind,input.name(),input.schoolCode(),parent,school,status));}
        else changed=organizations.update(IdentityManagementServer.principal(request),new OrganizationTransactionKernel.Update(ManagementFields.id(input.id()),ManagementFields.id(input.expectedVersion()),input.name(),parent,status),new OrganizationTransactionKernel.Identity(kind,input.schoolCode(),school));
        return ResultMessage.success(Map.of("id",Long.toString(changed.id()),"version",Long.toString(changed.version()),"accessEpoch",Long.toString(changed.accessEpoch())));
    }
    @Override public ResultMessage school(Reference input){var school=organizations.school(IdentityManagementServer.principal(request),ManagementFields.id(input.id()));
        return ResultMessage.success(Map.of("schoolId",Long.toString(school.schoolId()),"tenantId",Long.toString(school.tenantId()),"schoolCode",school.schoolCode(),"version",Long.toString(school.version()),"accessEpoch",Long.toString(school.accessEpoch())));}
    private static boolean mode(String value){if("CREATE".equals(value))return true;if("UPDATE".equals(value))return false;throw ManagementFields.invalid();}
    private static FoundationStatus status(String value){try{return FoundationStatus.valueOf(value);}catch(IllegalArgumentException|NullPointerException failure){throw ManagementFields.invalid();}}
}
