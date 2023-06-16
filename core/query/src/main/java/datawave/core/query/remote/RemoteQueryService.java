package datawave.core.query.remote;

import datawave.webservice.result.BaseQueryResponse;
import datawave.webservice.result.GenericResponse;
import datawave.webservice.result.VoidResponse;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * A remote query service is one that can pass calls off to another query service
 */
public interface RemoteQueryService {
    
    /**
     * Call the create on a remote query service
     * 
     * @param queryLogicName
     *            a query logic name
     * @param queryParameters
     *            the query parameters
     * @param callerObject
     *            the caller
     * @return the generic response
     */
    GenericResponse<String> createQuery(String queryLogicName, Map<String,List<String>> queryParameters, Object callerObject);
    
    /**
     * Call next on a remote query service
     * 
     * @param id
     *            the id
     * @param callerObject
     *            the caller
     * @return the base query response
     */
    BaseQueryResponse next(String id, Object callerObject);
    
    /**
     * Call close on a remote query service
     * 
     * @param id
     *            the id
     * @param callerObject
     *            the caller
     * @return the void response
     */
    VoidResponse close(String id, Object callerObject);
    
    /**
     * Plan a query using a remote query service
     *
     * @param queryLogicName
     *            a query logic name
     * @param queryParameters
     *            the query parameters
     * @param callerObject
     *            the caller
     * @return the generic response
     */
    GenericResponse<String> planQuery(String queryLogicName, Map<String,List<String>> queryParameters, Object callerObject);
    
    /**
     * Get the plan from a remote query service
     *
     * @param id
     *            the id
     * @param callerObject
     *            the caller
     * @return a generic response
     */
    GenericResponse<String> planQuery(String id, Object callerObject);
    
    /**
     * Get the URI for the query metrics
     * 
     * @param id
     *            the id
     * @return the query metrics uri
     */
    URI getQueryMetricsURI(String id);
}