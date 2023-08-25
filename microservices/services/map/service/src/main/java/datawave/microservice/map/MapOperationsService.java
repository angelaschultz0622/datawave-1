package datawave.microservice.map;

import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.commons.jexl2.parser.ASTJexlScript;
import org.apache.commons.jexl2.parser.ParseException;
import org.apache.http.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import datawave.core.query.jexl.JexlASTHelper;
import datawave.data.type.Type;
import datawave.microservice.authorization.user.DatawaveUserDetails;
import datawave.microservice.map.config.MapServiceProperties;
import datawave.microservice.map.data.GeoFeatures;
import datawave.microservice.map.visitor.GeoFeatureVisitor;
import datawave.query.util.MetadataHelper;
import datawave.security.authorization.DatawaveUser;
import datawave.security.authorization.JWTTokenHandler;
import datawave.webservice.dictionary.data.DataDictionaryBase;
import datawave.webservice.metadata.MetadataFieldBase;

@Service
public class MapOperationsService {
    private final Logger log = LoggerFactory.getLogger(this.getClass());
    
    private final MapServiceProperties mapServiceProperties;
    private final WebClient webClient;
    private final JWTTokenHandler jwtTokenHandler;
    private final MetadataHelper metadataHelper;
    private final Set<String> geoFields = new HashSet<>();
    private final Set<String> geoWaveFields = new HashSet<>();
    
    public MapOperationsService(MapServiceProperties mapServiceProperties, WebClient.Builder webClientBuilder, JWTTokenHandler jwtTokenHandler,
                    MetadataHelper metadataHelper) {
        this.mapServiceProperties = mapServiceProperties;
        this.webClient = webClientBuilder.build();
        this.jwtTokenHandler = jwtTokenHandler;
        this.metadataHelper = metadataHelper;
        loadGeoFields();
    }
    
    public void loadGeoFields() {
        geoFields.clear();
        geoWaveFields.clear();
        geoFields.addAll(mapServiceProperties.getGeoFields());
        geoWaveFields.addAll(mapServiceProperties.getGeoWaveFields());
        loadGeoFieldsFromDictionary();
    }
    
    private void loadGeoFieldsFromDictionary() {
        DataDictionaryBase<?,?> dictionary = null;
        try {
            // @formatter:off
            dictionary = webClient
                    .get()
                    .uri(UriComponentsBuilder
                            .fromHttpUrl(mapServiceProperties.getDictionaryUri())
                            .toUriString())
                    .header(HttpHeaders.AUTHORIZATION, createBearerHeader())
                    .retrieve()
                    .toEntity(DataDictionaryBase.class)
                    .block()
                    .getBody();
            // @formatter:on
        } catch (IllegalStateException e) {
            log.warn("Timed out waiting for remote authorization response");
        }
        
        if (dictionary != null && dictionary.getFields() != null) {
            for (MetadataFieldBase<?,?> field : dictionary.getFields()) {
                boolean geoField = false;
                boolean geowaveField = false;
                for (String type : field.getTypes()) {
                    if (mapServiceProperties.getGeoTypes().contains(type)) {
                        geoFields.add(field.getFieldName());
                        geoField = true;
                    }
                    if (mapServiceProperties.getGeoWaveTypes().contains(type)) {
                        geoWaveFields.add(field.getFieldName());
                        geowaveField = true;
                    }
                    if (geoField && geowaveField) {
                        break;
                    }
                }
            }
        }
    }
    
    // We could save the jwt token for reuse, but it will eventually expire. Since this should be used infrequently (i.e. when loading the dictionary) let's
    // generate it each time.
    protected String createBearerHeader() {
        final String jwt;
        try {
            // @formatter:off
            jwt = webClient.get()
                    .uri(URI.create(mapServiceProperties.getAuthorizationUri()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));
            // @formatter:on
        } catch (IllegalStateException e) {
            throw new IllegalStateException("Timed out waiting for remote authorization response", e);
        }
        
        Collection<DatawaveUser> principals = jwtTokenHandler.createUsersFromToken(jwt);
        long createTime = principals.stream().map(DatawaveUser::getCreationTime).min(Long::compareTo).orElse(System.currentTimeMillis());
        DatawaveUserDetails userDetails = new DatawaveUserDetails(principals, createTime);
        
        return "Bearer " + jwtTokenHandler.createTokenFromUsers(userDetails.getPrimaryUser().getName(), userDetails.getProxiedUsers());
    }
    
    public GeoFeatures getGeoFeatures(String plan) {
        ASTJexlScript script;
        try {
            script = JexlASTHelper.parseAndFlattenJexlQuery(plan);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
        
        Map<String,Set<Type<?>>> typesByField = null;
        try {
            // TODO: Update visitor to accept metadata helper or map of fields and types
            // @formatter:off
            typesByField = metadataHelper.getFieldsToDatatypes(null).asMap().entrySet().stream()
                    .map(x -> Map.entry(x.getKey(), new LinkedHashSet<>(x.getValue())))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            // @formatter:on
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        
        return GeoFeatureVisitor.getGeoFeatures(script, typesByField);
    }
    
    public Set<String> getGeoFields() {
        return geoFields;
    }
    
    public Set<String> getGeoWaveFields() {
        return geoWaveFields;
    }
}
