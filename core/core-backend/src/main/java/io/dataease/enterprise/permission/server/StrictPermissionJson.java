package io.dataease.enterprise.permission.server;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataease.api.permissions.enterprise.PermissionContract;
import io.dataease.api.permissions.enterprise.PermissionContract.*;
import io.dataease.enterprise.management.server.ManagementFields;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Closed recursive grammar; does not alter the legacy or community mapper. */
public final class StrictPermissionJson extends AbstractHttpMessageConverter<PermissionContract.Request> {
    private static final Set<Class<?>> RECORDS = Set.of(Subject.class, ResourceScope.class, SchoolScope.class,
            Change.class, CapabilityChange.class, Batch.class, CapabilityBatch.class,
            Catalog.class, RulesPage.class, CapabilitiesPage.class);
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                    .maxStringLength(512).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public StrictPermissionJson() { super(MediaType.APPLICATION_JSON); }
    @Override protected boolean supports(Class<?> type) { return PermissionContract.Request.class.isAssignableFrom(type); }
    @Override protected PermissionContract.Request readInternal(Class<? extends PermissionContract.Request> type,
                                                                HttpInputMessage input) throws IOException {
        byte[] bytes = input.getBody().readNBytes(65_537);
        try {
            if (bytes.length > 65_536) throw ManagementFields.invalid();
            String text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            var tree = mapper.readTree(text);
            validate(tree, type);
            return mapper.treeToValue(tree, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | java.nio.charset.CharacterCodingException failure) { throw ManagementFields.invalid(); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }
    private void validate(JsonNode node, Type type) {
        if (node == null || node.isNull()) throw ManagementFields.invalid();
        if (type == String.class) { if (!node.isTextual()) throw ManagementFields.invalid(); return; }
        if (type == Integer.class) { if (!node.isIntegralNumber() || !node.canConvertToInt()) throw ManagementFields.invalid(); return; }
        if (type instanceof ParameterizedType list && list.getRawType() == List.class) {
            Type item = list.getActualTypeArguments()[0];
            if (!Set.of(String.class, Change.class, CapabilityChange.class).contains(item)
                    || !node.isArray() || node.size() > (item == String.class ? 500 : 200)) throw ManagementFields.invalid();
            for (var value : node) validate(value, item);
            return;
        }
        if (!(type instanceof Class<?> record) || !RECORDS.contains(record) || !node.isObject()) throw ManagementFields.invalid();
        var components = record.getRecordComponents();
        var fields = Arrays.stream(components).map(java.lang.reflect.RecordComponent::getName).collect(Collectors.toSet());
        var names = node.fieldNames();
        while (names.hasNext()) if (!fields.contains(names.next())) throw ManagementFields.invalid();
        for (var component : components) if (node.has(component.getName())) validate(node.get(component.getName()), component.getGenericType());
    }
    @Override protected void writeInternal(PermissionContract.Request value, HttpOutputMessage output) {
        throw new IllegalStateException("Permission requests are not response models");
    }
}
