package datawave.microservice.map.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GeoFeatures {
    private List<GeoFunctionFeature> geoFunctions;
    private Map<String,AbstractGeoTerms> geoByField;
    
    public GeoFeatures() {
        geoFunctions = new ArrayList<>();
        geoByField = new HashMap<>();
    }
    
    public GeoFeatures(List<GeoFunctionFeature> geoFunctions, Map<String,AbstractGeoTerms> geoByField) {
        this.geoFunctions = geoFunctions;
        this.geoByField = geoByField;
    }
    
    public List<GeoFunctionFeature> getFunctions() {
        return geoFunctions;
    }
    
    public void setFunctions(List<GeoFunctionFeature> geoFunctions) {
        this.geoFunctions = geoFunctions;
    }
    
    public Map<String,AbstractGeoTerms> getGeoByField() {
        return geoByField;
    }
    
    public void setGeoByField(Map<String,AbstractGeoTerms> geoByField) {
        this.geoByField = geoByField;
    }
}
