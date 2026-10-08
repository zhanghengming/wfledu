package io.dataease.enterprise.management.server;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataease.api.permissions.enterprise.ManagementContract;
import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Scoped to new DTOs. Community canvas serialization and coercion remain unchanged. */
public final class StrictManagementJson extends AbstractHttpMessageConverter<ManagementContract.Request> {
    private final ObjectMapper mapper=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(512).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public StrictManagementJson(){super(MediaType.APPLICATION_JSON);}
    @Override protected boolean supports(Class<?> type){return ManagementContract.Request.class.isAssignableFrom(type);}
    @Override protected ManagementContract.Request readInternal(Class<? extends ManagementContract.Request> type,HttpInputMessage input) throws IOException {
        byte[] bytes=input.getBody().readNBytes(65_537);
        try {
            if(bytes.length>65_536 || !type.isRecord())throw invalid();
            var tree=mapper.readTree(bytes);
            if(tree==null || !tree.isObject())throw invalid();
            var components=type.getRecordComponents();Set<String> fields=Arrays.stream(components).map(java.lang.reflect.RecordComponent::getName).collect(Collectors.toSet());
            var names=tree.fieldNames();while(names.hasNext())if(!fields.contains(names.next()))throw invalid();
            for(var component:components){var value=tree.get(component.getName());if(value==null || value.isNull())continue;
                if(component.getType()==String.class || component.getType()==char[].class){if(!value.isTextual())throw invalid();}
                else if(component.getType()==Integer.class){if(!value.isIntegralNumber() || !value.canConvertToInt())throw invalid();}
                else if(component.getType()==java.util.List.class){if(!value.isArray() || value.size()>500)throw invalid();for(var item:value)if(!item.isTextual())throw invalid();}
                else throw invalid();
            }
            if(type==ManagementContract.OrganizationSave.class || type==ManagementContract.MemberSave.class){
                var mode=tree.get("mode");if(mode==null || !mode.isTextual() || !Set.of("CREATE","UPDATE").contains(mode.textValue()))throw invalid();
                if(mode.textValue().equals("CREATE") && (tree.has("id") || tree.has("expectedVersion")))throw invalid();
                if(mode.textValue().equals("UPDATE") && (!tree.hasNonNull("id") || !tree.hasNonNull("expectedVersion")))throw invalid();
            }
            return mapper.treeToValue(tree,type);
        } catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw invalid();}
        finally{Arrays.fill(bytes,(byte)0);}
    }
    @Override protected void writeInternal(ManagementContract.Request value,HttpOutputMessage output){throw new IllegalStateException("Management requests are not response models");}
    private static DEException invalid(){return new DEException(ResultCode.PARAM_IS_INVALID.code(),ResultCode.PARAM_IS_INVALID.message());}
}
