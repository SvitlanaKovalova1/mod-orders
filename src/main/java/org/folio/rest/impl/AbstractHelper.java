package org.folio.rest.impl;

import static javax.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static org.folio.orders.utils.HelperUtils.OKAPI_URL;
import static org.folio.rest.RestVerticle.OKAPI_HEADER_TENANT;

import io.vertx.core.Context;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.folio.orders.rest.exceptions.HttpException;
import org.folio.rest.jaxrs.model.Error;
import org.folio.rest.jaxrs.model.Errors;
import org.folio.rest.tools.client.HttpClientFactory;
import org.folio.rest.tools.client.interfaces.HttpClientInterface;
import org.folio.rest.tools.utils.TenantTool;

public abstract class AbstractHelper {
  public static final String ID = "id";

  protected final Logger logger = LoggerFactory.getLogger(this.getClass());

  private final Errors processingErrors = new Errors();

  protected final HttpClientInterface httpClient;
  protected final Map<String, String> okapiHeaders;
  protected final Context ctx;
  protected final String lang;

  AbstractHelper(HttpClientInterface httpClient, Map<String, String> okapiHeaders, Context ctx, String lang) {
    this.httpClient = httpClient;
    this.okapiHeaders = okapiHeaders;
    this.ctx = ctx;
    this.lang = lang;
    setDefaultHeaders();
  }

  AbstractHelper(Map<String, String> okapiHeaders, Context ctx, String lang) {
    this.httpClient = getHttpClient(okapiHeaders);
    this.okapiHeaders = okapiHeaders;
    this.ctx = ctx;
    this.lang = lang;
    setDefaultHeaders();
  }

  public static HttpClientInterface getHttpClient(Map<String, String> okapiHeaders) {
    final String okapiURL = okapiHeaders.getOrDefault(OKAPI_URL, "");
    final String tenantId = TenantTool.calculateTenantId(okapiHeaders.get(OKAPI_HEADER_TENANT));

    return HttpClientFactory.getHttpClient(okapiURL, tenantId);
  }

  public void closeHttpClient() {
    httpClient.closeClient();
  }

  public List<Error> getErrors() {
    return processingErrors.getErrors();
  }

  /**
   * Some requests do not have body and in happy flow do not produce response body. The Accept header is required for calls to storage
   */
  private void setDefaultHeaders() {
    Map<String,String> customHeader = new HashMap<>();
    customHeader.put(HttpHeaders.ACCEPT.toString(), APPLICATION_JSON  + ", " + TEXT_PLAIN);
    httpClient.setDefaultHeaders(customHeader);
  }

  protected Errors getProcessingErrors() {
    processingErrors.setTotalRecords(processingErrors.getErrors().size());
    return processingErrors;
  }

  protected void addProcessingError(Error error) {
    processingErrors.getErrors().add(error);
  }

  protected void addProcessingErrors(List<Error> errors) {
    processingErrors.getErrors().addAll(errors);
  }

  protected int handleProcessingError(Throwable throwable) {
    final Throwable cause = throwable.getCause();
    logger.error("Exception encountered", cause);
    final Error error = new Error().withMessage(cause.getMessage());
    final int code;

    if (cause instanceof HttpException) {
      code = ((HttpException) cause).getCode();
      error.withCode(((HttpException) cause).getErrorCode());
    } else {
      code = INTERNAL_SERVER_ERROR.getStatusCode();
    }

    addProcessingError(error);

    return code;
  }

  public Response buildErrorResponse(Throwable throwable) {
    return buildErrorResponse(handleProcessingError(throwable));
  }

  public Response buildErrorResponse(int code) {
    final Response.ResponseBuilder responseBuilder;
    switch (code) {
      case 400:
      case 404:
      case 422:
        responseBuilder = Response.status(code);
        break;
      default:
        responseBuilder = Response.status(INTERNAL_SERVER_ERROR);
    }
    closeHttpClient();

    return responseBuilder
      .header(CONTENT_TYPE, APPLICATION_JSON)
      .entity(getProcessingErrors())
      .build();
  }

  public Response buildOkResponse(Object body) {
    closeHttpClient();
    return Response.ok(body, APPLICATION_JSON).build();
  }

  public Response buildNoContentResponse() {
    closeHttpClient();
    return Response.noContent().build();
  }
}
