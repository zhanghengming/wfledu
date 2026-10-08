package io.dataease.api.permissions.enterprise;

import java.util.List;

/** Versioned W03 control-plane requests. IDs and revisions use positive decimal strings. */
public final class ManagementContract {
    private ManagementContract() { }
    public interface Request { }
    public record Login(String username,char[] password) implements Request {
        @Override public String toString(){return "Management login [redacted]";}
    }
    public record PasswordChange(char[] previousPassword,char[] newPassword) implements Request {
        @Override public String toString(){return "Management password change [redacted]";}
    }
    public record Empty() implements Request { }
    public record Switch(String tenantId,String expectedVersion) implements Request { }
    public record Page(Integer pageNum,Integer pageSize) implements Request { }
    public record OrganizationSave(String mode,String id,String expectedVersion,String kind,String name,String schoolCode,
                                   String parentId,String schoolId,String status) implements Request { }
    public record MemberSave(String mode,String id,String expectedVersion,String userId,String status,List<String> organizationIds) implements Request { }
    public record Reference(String id) implements Request { }
    public record TenantCreate(String code,String name,String administratorUserId) implements Request { }
    public record UserCreate(String username,String displayName,char[] temporaryPassword) implements Request {
        @Override public String toString(){return "Platform user create [redacted]";}
    }
    public record ResourceCreate(String name) implements Request { }
    public record ResourceRead(String id,String action) implements Request { }
}
