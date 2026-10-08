package io.dataease.enterprise.management.server;

import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;

public final class ManagementFields {
    private ManagementFields(){ }
    public static long id(String value){
        if(value==null || !value.matches("[1-9][0-9]{0,18}"))throw invalid();
        try{return Long.parseLong(value);}catch(NumberFormatException failure){throw invalid();}
    }
    public static Long optionalId(String value){return value==null?null:id(value);}
    public static String text(String value,int maximum){
        io.dataease.enterprise.tenant.domain.OrganizationHierarchy.requireText(value,maximum);
        return value;
    }
    public static int page(Integer value){int number=value==null?1:value;if(number<1 || number>10000)throw invalid();return number;}
    public static int size(Integer value){int number=value==null?20:value;if(number<1 || number>100)throw invalid();return number;}
    public static DEException invalid(){return new DEException(ResultCode.PARAM_IS_INVALID.code(),ResultCode.PARAM_IS_INVALID.message());}
}
