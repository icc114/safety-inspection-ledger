package cn.safetyledger.app.data;

import java.util.*;

public final class Entities {
    private Entities() {}
    public static final class Template {
        public String id,name,category; public boolean active; public long updatedAt;
        public final List<TemplateItem> items=new ArrayList<>();
    }
    public static final class TemplateItem {
        public String id,templateId,category,content,standard; public int order; public boolean active;
    }
    public static final class Inspection {
        public String id,templateId,templateName,date,time,type,unit,location,onDuty,inspector1,inspector2,inspectee,conclusion,advice,responsible,deadline,rectification,recheck,status,deviceId;
        public long createdAt,updatedAt; public Long deletedAt; public final List<InspectionItem> items=new ArrayList<>(); public final List<Media> media=new ArrayList<>();
    }
    public static final class InspectionItem {
        public String id,inspectionId,templateItemId,category,content,standard,result,problem; public int order;
    }
    public static final class Media {
        public String id,inspectionId,itemId,category,localPath,remoteKey,location,sha256,mime; public long capturedAt,size; public Double latitude,longitude;
    }
    public static final class Signature { public String id,inspectionId,role,path,sha256; }
    public static final class Page<T> { public List<T> rows=new ArrayList<>(); public int total,page,pageSize; }
}
