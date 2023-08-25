package datawave.microservice.map.data;

import java.util.Set;

import org.opengis.feature.Feature;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import datawave.microservice.map.data.serialization.FeatureDeserializer;
import datawave.microservice.map.data.serialization.FeatureSerializer;

public class GeoFunctionFeature {
    private String function;
    private Set<String> fields;
    @JsonSerialize(using = FeatureSerializer.class)
    @JsonDeserialize(using = FeatureDeserializer.class)
    private Feature geoJson;
    
    public GeoFunctionFeature() {}
    
    public GeoFunctionFeature(String function, Set<String> fields, Feature geoJson) {
        this.function = function;
        this.fields = fields;
        this.geoJson = geoJson;
    }
    
    public String getFunction() {
        return function;
    }
    
    public void setFunction(String function) {
        this.function = function;
    }
    
    public Set<String> getFields() {
        return fields;
    }
    
    public void setFields(Set<String> fields) {
        this.fields = fields;
    }
    
    public Feature getGeoJson() {
        return geoJson;
    }
    
    public void setGeoJson(Feature geoJson) {
        this.geoJson = geoJson;
    }
}
