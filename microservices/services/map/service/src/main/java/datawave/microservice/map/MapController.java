package datawave.microservice.map;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import datawave.microservice.map.data.GeoFeatures;

@RestController
@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class MapController {
    private MapOperationsService mapOperationsService;
    
    public MapController(MapOperationsService mapOperationsService) {
        this.mapOperationsService = mapOperationsService;
    }
    
    // returns the fields mapped by type (geo vs geowave)
    @RequestMapping(path = "/fieldsByType", method = {RequestMethod.GET})
    public Map<String,Set<String>> fieldsByType() {
        Map<String,Set<String>> fieldsByType = new HashMap<>();
        fieldsByType.put("geo", mapOperationsService.getGeoFields());
        fieldsByType.put("geowave", mapOperationsService.getGeoWaveFields());
        return fieldsByType;
    }
    
    // reload fields from configuration and the data dictionary
    @RequestMapping(path = "/reloadFieldsByType", method = {RequestMethod.POST})
    public void reloadFieldsByType() {
        mapOperationsService.loadGeoFields();
    }
    
    @RequestMapping(path = "/getGeoFeatures", method = {RequestMethod.POST})
    public GeoFeatures getGeoFeatures(@RequestParam("plan") String plan) {
        return mapOperationsService.getGeoFeatures(plan);
    }
}
