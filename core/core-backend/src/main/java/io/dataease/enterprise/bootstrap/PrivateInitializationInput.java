package io.dataease.enterprise.bootstrap;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataease.enterprise.identity.manage.PlatformInitialization;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

/** No default path; local owner-only file is explicitly supplied by the operator. */
public final class PrivateInitializationInput {
    private PrivateInitializationInput() { }
    public static void initialize(Path path,PlatformInitialization initializer) throws java.io.IOException {
        if(!path.isAbsolute() || !path.equals(path.normalize()) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))
                || !Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).getName().equals(System.getProperty("user.name")) || Files.size(path)>4096)
            throw new IllegalStateException("Initialization requires an absolute owner-only regular input file");
        var mapper=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
        mapper.enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        byte[] bytes=Files.readAllBytes(path);
        try {
            var json=mapper.readTree(bytes);
            if(json==null || !json.isObject() || json.size()!=4 || !json.has("username") || !json.has("displayName") || !json.has("password") || !json.has("qualifications")
                    || !json.get("username").isTextual() || !json.get("displayName").isTextual() || !json.get("password").isTextual() || !json.get("qualifications").isArray())
                throw new IllegalStateException("Invalid initialization input structure");
            Set<String> qualifications=new HashSet<>();
            for(var value:json.get("qualifications"))if(!value.isTextual() || !qualifications.add(value.textValue()))throw new IllegalStateException("Invalid initialization qualifications");
            initializer.initialize(json.get("username").textValue(),json.get("displayName").textValue(),json.get("password").textValue().toCharArray(),qualifications);
        } catch(com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Invalid initialization JSON");
        } finally {java.util.Arrays.fill(bytes,(byte)0);}
    }
}
