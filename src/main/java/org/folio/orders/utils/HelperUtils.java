package org.folio.orders.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
import org.folio.orders.rest.exceptions.HttpException;
import org.folio.rest.jaxrs.model.Adjustment;
import org.folio.rest.jaxrs.model.PoLine;
import org.folio.rest.tools.client.Response;
import org.folio.rest.tools.client.interfaces.HttpClientInterface;

import io.vertx.core.Context;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import me.escoffier.vertx.completablefuture.VertxCompletableFuture;

import static java.util.Objects.nonNull;

public class HelperUtils {
  private static final String PO_LINES = "po_lines";

  private static final String EXCEPTION_CALLING_ENDPOINT_MSG = "Exception calling {} {}";

  private static final Map<String,String> subObjectApis = new HashMap<>();

  static {
    subObjectApis.put("adjustment", "/adjustment/"); 
    subObjectApis.put("cost","/cost/");
    subObjectApis.put("details", "/details/");
    subObjectApis.put("eresource", "/eresource/");
    subObjectApis.put("location", "/location/");
    subObjectApis.put("physical", "/physical/");
    subObjectApis.put("renewal", "/renewal/");
    subObjectApis.put("source", "/source/");
    subObjectApis.put("vendor_detail", "/vendor_detail/");
    subObjectApis.put("alerts", "/alert/");
    subObjectApis.put("claims", "/claim/");
    subObjectApis.put("reporting_codes", "/reporting_code/");
    subObjectApis.put("fund_distribution", "/fund_distribution/");
    subObjectApis.put(PO_LINES, "/po_line/");
  }

  private static final String GET_POLINE_EXCEPTION = "Exception calling GET /po_line/";

  private HelperUtils() {

  }

  public static String getMockData(String path) throws IOException {
    try {
      return IOUtils.toString(HelperUtils.class.getClassLoader().getResourceAsStream(path), StandardCharsets.UTF_8);
    } catch (NullPointerException e) {
      StringBuilder sb = new StringBuilder();
      try (Stream<String> lines = Files.lines(Paths.get(path))) {
        lines.forEach(sb::append);
      }
      return sb.toString();
    }
  }

  public static JsonObject verifyAndExtractBody(Response response) {
    if (!Response.isSuccess(response.getCode())) {
      throw new CompletionException(
          new HttpException(response.getCode(), response.getError().getString("errorMessage")));
    }

    return response.getBody();
  }

  /**
   * If response http code is {@code 404}, empty {@link JsonObject} is returned. Otherwise verifies if response is successful and extracts body
   * In case there was failed attempt to delete PO or particular PO line, the sub-objects might be already partially deleted.
   * This check allows user to retry DELETE operation
   *
   * @param response response to verify
   * @return empty {@link JsonObject} if response http code is {@code 404}, otherwise verifies if response is successful and extracts body
   */
  private static JsonObject verifyAndExtractBodyIfFound(Response response) {
    if (response.getCode() == 404) {
      return new JsonObject();
    }
    return verifyAndExtractBody(response);
  }

  public static Adjustment calculateAdjustment(List<PoLine> lines) {
    Adjustment ret = null;
    for (PoLine line : lines) {
      Adjustment a = line.getAdjustment();
      if (a != null) {
        if (ret == null) {
          ret = a;
        } else {
          ret.setCredit(accumulate(ret.getCredit(), a.getCredit()));
          ret.setDiscount(accumulate(ret.getDiscount(), a.getDiscount()));
          ret.setInsurance(accumulate(ret.getInsurance(), a.getInsurance()));
          ret.setOverhead(accumulate(ret.getOverhead(), a.getOverhead()));
          ret.setShipment(accumulate(ret.getShipment(), a.getShipment()));
          ret.setTax1(accumulate(ret.getTax1(), a.getTax1()));
          ret.setTax2(accumulate(ret.getTax2(), a.getTax2()));
        }
      }
    }
    return ret;
  }

  private static double accumulate(Double a, Double b) {
    if (a == null && b == null)
      return 0d;
    if (a == null)
      return b;
    if (b == null)
      return a;

    return (a + b);
  }

  public static CompletableFuture<JsonObject> getPurchaseOrder(String id, String lang, HttpClientInterface httpClient, Context ctx, Map<String, String> okapiHeaders, Logger logger) {
    String endpoint = String.format("/purchase_order/%s?lang=%s", id, lang);
    return handleGetRequest(endpoint, httpClient, ctx, okapiHeaders, logger);
  }

  /**
   *  Retrieves PO lines from storage by PO id as JsonObject with array of po_lines (/acq-models/mod-orders-storage/schemas/po_line.json objects)
   */
  public static CompletableFuture<JsonObject> getPoLines(String id, String lang, HttpClientInterface httpClient, Context ctx, Map<String, String> okapiHeaders, Logger logger) {
    String endpoint = String.format("/po_line?limit=999&query=purchase_order_id==%s&lang=%s", id, lang);
    return handleGetRequest(endpoint, httpClient, ctx, okapiHeaders, logger);
  }

  /**
   * Retrieves PO line from storage by PO line id as JsonObject (/acq-models/mod-orders-storage/schemas/po_line.json object)
   */
  public static CompletableFuture<JsonObject> getPoLineById(String lineId, String lang, HttpClientInterface httpClient, Context ctx,
                                                            Map<String, String> okapiHeaders, Logger logger) {
    String endpoint = String.format("%s%s?lang=%s", subObjectApis.get(PO_LINES), lineId, lang);
    return handleGetRequest(endpoint, httpClient, ctx, okapiHeaders, logger);
  }

	public static CompletableFuture<Void> deletePoLines(String orderId, String lang, HttpClientInterface httpClient,
      Context ctx, Map<String, String> okapiHeaders, Logger logger) {

    return getPoLines(orderId, lang, httpClient,ctx, okapiHeaders, logger)
      .thenCompose(body -> {
        List<CompletableFuture<JsonObject>> futures = new ArrayList<>();

        for (int i = 0; i < body.getJsonArray(PO_LINES).size(); i++) {
          JsonObject line = body.getJsonArray(PO_LINES).getJsonObject(i);
          futures.add(deletePoLine(line, httpClient, ctx, okapiHeaders, logger));
        }

        return VertxCompletableFuture.allOf(ctx, futures.toArray(new CompletableFuture[0]));
      })
      .exceptionally(t -> {
        logger.error("Exception deleting po_line data for order id={}:", t, orderId);
        throw new CompletionException(t);
      });
  }

  public static CompletableFuture<JsonObject> deletePoLine(JsonObject line, HttpClientInterface httpClient, Context ctx, Map<String, String> okapiHeaders, Logger logger) {
    return operateOnPoLine(HttpMethod.DELETE, line, httpClient, ctx, okapiHeaders, logger)
      .thenCompose(poline -> {
        String polineId = poline.getId();
        return operateOnSubObj(HttpMethod.DELETE, subObjectApis.get(PO_LINES) + polineId, httpClient, ctx, okapiHeaders, logger);
      });
  }

  public static CompletableFuture<List<PoLine>> getCompositePoLines(String id, String lang, HttpClientInterface httpClient, Context ctx, Map<String, String> okapiHeaders, Logger logger) {
    CompletableFuture<List<PoLine>> future = new VertxCompletableFuture<>(ctx);
  
      getPoLines(id,lang, httpClient,ctx, okapiHeaders, logger)
        .thenAccept(body -> {
          List<PoLine> lines = new ArrayList<>();
          List<CompletableFuture<Void>> futures = new ArrayList<>();

          for (int i = 0; i < body.getJsonArray(PO_LINES).size(); i++) {
            JsonObject line = body.getJsonArray(PO_LINES).getJsonObject(i);
            futures.add(operateOnPoLine(HttpMethod.GET, line, httpClient, ctx, okapiHeaders, logger)
              .thenAccept(lines::add));
          }

          VertxCompletableFuture.allOf(ctx, futures.toArray(new CompletableFuture[0]))
            .thenAccept(v -> future.complete(lines))
            .exceptionally(t -> {
              future.completeExceptionally(t.getCause());
              return null;
            });
        })
        .exceptionally(t -> {
          logger.error("Exception gathering po_line data:", t);
          throw new CompletionException(t);
        });
    return future;
  }

  public static CompletableFuture<PoLine> operateOnPoLine(HttpMethod operation,JsonObject line, HttpClientInterface httpClient, Context ctx, Map<String, String> okapiHeaders, Logger logger) {
    CompletableFuture<PoLine> future = new VertxCompletableFuture<>(ctx);

    if (logger.isDebugEnabled()) {
      logger.debug("The PO line prior to {} operation: {}", operation, line.encodePrettily());
    }

    List<CompletableFuture<Void>> futures = new ArrayList<>();
    futures.add(operateOnSubObjIfPresent(operation, line, "adjustment", httpClient, ctx, okapiHeaders, logger));
    futures.add(operateOnSubObjIfPresent(operation, line, "cost", httpClient, ctx, okapiHeaders, logger));
    futures.add(operateOnSubObjIfPresent(operation, line, "details", httpClient, ctx, okapiHeaders, logger));
    futures.add(operateOnSubObjIfPresent(operation, line, "eresource", httpClient, ctx, okapiHeaders, logger));
    futures.add(operateOnSubObjIfPresent(operation, line, "location", httpClient, ctx, okapiHeaders, logger));
    futures.add(operateOnSubObjIfPresent(operation, line, "physical", httpClient, ctx, okapiHeaders, logger));
    futures.add(operateOnSubObjIfPresent(operation, line, "renewal", httpClient, ctx, okapiHeaders, logger));
    futures.add(operateOnSubObjIfPresent(operation, line, "source", httpClient, ctx, okapiHeaders, logger));
    futures.add(operateOnSubObjIfPresent(operation, line, "vendor_detail", httpClient, ctx, okapiHeaders, logger));
    futures.addAll(operateOnSubObjsIfPresent(operation, line, "alerts", httpClient, ctx, okapiHeaders, logger));
    futures.addAll(operateOnSubObjsIfPresent(operation, line, "claims", httpClient, ctx, okapiHeaders, logger));
    futures.addAll(operateOnSubObjsIfPresent(operation, line, "fund_distribution", httpClient, ctx, okapiHeaders, logger));

    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
      .thenAccept(v -> {
        if (logger.isDebugEnabled()) {
          logger.debug("The PO line after {} operation on sub-objects: {}", operation, line.encodePrettily());
        }
        future.complete(line.mapTo(PoLine.class));
      })
      .exceptionally(t -> {
        logger.error("Exception resolving one or more po_line sub-object(s) on {} operation:", t, operation);
        future.completeExceptionally(t);
        return null;
      });
    return future;
  }

  private static List<CompletableFuture<Void>> operateOnSubObjsIfPresent(HttpMethod operation, JsonObject pol, String field,
                                                                HttpClientInterface httpClient, Context ctx, Map<String, String> okapiHeaders, Logger logger) {
    JsonArray array = new JsonArray();
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    ((List<?>) pol.remove(field))
      .forEach(fundDistroId -> futures.add(operateOnSubObj(operation, subObjectApis.get(field) + fundDistroId, httpClient, ctx, okapiHeaders, logger)
                .thenAccept(array::add)));
    pol.put(field, array);
    return futures;
  }

  private static CompletableFuture<Void> operateOnSubObjIfPresent(HttpMethod operation, JsonObject pol, String field, HttpClientInterface httpClient, Context ctx, Map<String, String> okapiHeaders, Logger logger) {
    String id = (String) pol.remove(field);
    if (id != null) {
      return operateOnSubObj(operation, subObjectApis.get(field) + id, httpClient, ctx, okapiHeaders, logger)
        .thenAccept(json -> {
          if (json != null) {
            if (!json.isEmpty()) {
              pol.put(field, json);
            } else if (HttpMethod.DELETE != operation) {
              logger.warn("The '{}' sub-object with id={} is empty for Order line with id={}", field, id, pol.getString("id"));
            }
          }
        });
    }
    return CompletableFuture.completedFuture(null);
  }
  
  public static CompletableFuture<JsonObject> operateOnSubObj(HttpMethod operation, String url,
      HttpClientInterface httpClient, Context ctx, Map<String, String> okapiHeaders, Logger logger) {
    CompletableFuture<JsonObject> future = new VertxCompletableFuture<>(ctx);

    logger.info("Calling {} {}", operation, url);

    try {
      if (url.startsWith("/fund_distribution/")) {
        JsonObject mockFundDistribution = new JsonObject()
          .put("id", "mocki-dfix-modo-rders19first")
          .put("code", "EUROHIST-FY19")
          .put("percentage", "100.0")
          .put("encumbrance", "eb506834-6c70-4239-8d1a-6414a5b08003");
        future.complete(mockFundDistribution);
        future.complete(mockFundDistribution);
      }
      httpClient.request(operation, url, okapiHeaders)
        // In case there was failed attempt to delete order or particular PO line, the sub-objects might be already partially deleted.
        .thenApply(HelperUtils::verifyAndExtractBodyIfFound)
        .thenAccept(json -> {
          if (json != null) {
            if (json.isEmpty()) {
              logger.warn("The {} {} operation completed with empty response body", operation, url);
            } else if (logger.isInfoEnabled()) {
              logger.info("The {} {} operation completed with following response body: {}", operation, url, json.encodePrettily());
            }
            future.complete(json);
          } else {
            //Handling the delete API where it sends no response body
            logger.info("The {} {} operation completed with no response body", operation, url);
            future.complete(new JsonObject());
          }
        })
        .exceptionally(t -> {
          logger.error(EXCEPTION_CALLING_ENDPOINT_MSG, t, operation, url);
          future.completeExceptionally(t);
          return null;
        });
    } catch (Exception e) {
      logger.error("Exception performing http request {} {}", e, operation, url);
      future.completeExceptionally(e);
    }

    return future;
  }

  private static CompletableFuture<JsonObject> handleGetRequest(String endpoint, HttpClientInterface httpClient, Context ctx, Map<String, String> okapiHeaders,
                                       Logger logger) {
    CompletableFuture<JsonObject> future = new VertxCompletableFuture<>(ctx);
    try {
      logger.debug("Calling GET {}", endpoint);

      httpClient
        .request(HttpMethod.GET, endpoint, okapiHeaders)
        .thenApply(response -> {
          logger.debug("Validating received response");
          return verifyAndExtractBody(response);
        })
        .thenAccept(body -> {
          if (logger.isDebugEnabled()) {
            logger.debug("The response is valid. The response body: {}", nonNull(body) ? body.encodePrettily() : null);
          }
          future.complete(body);
        })
        .exceptionally(t -> {
          logger.error(EXCEPTION_CALLING_ENDPOINT_MSG, t, HttpMethod.GET, endpoint);
          future.completeExceptionally(t);
          return null;
        });
    } catch (Exception e) {
      logger.error(EXCEPTION_CALLING_ENDPOINT_MSG, e, HttpMethod.GET, endpoint);
      future.completeExceptionally(e);
    }

    return future;
  }

  public static CompletableFuture<PoLine> getCompositePoLineById(String polineId, String lang, HttpClientInterface httpClient, Context ctx, Map<String, String> okapiHeaders, Logger logger) {
    CompletableFuture<PoLine> future = new VertxCompletableFuture<>(ctx);
    try {
      httpClient.request(HttpMethod.GET,
        String.format("/po_line/%s?lang=%s", polineId, lang), okapiHeaders)
        .thenApply(HelperUtils::verifyAndExtractBody)
        .thenCompose(poLine -> operateOnPoLine(HttpMethod.GET, poLine, httpClient, ctx, okapiHeaders, logger))
        .thenAccept(poline -> {
          if (logger.isDebugEnabled()) {
            logger.debug("The response is valid. The response body: {}",
              nonNull(poline) ? JsonObject.mapFrom(poline).encodePrettily() : null);
          }
          future.complete(poline);
        })
        .exceptionally(t -> {
          logger.error(GET_POLINE_EXCEPTION + polineId, t);
          future.completeExceptionally(t);
          return null;
        });
    } catch (Exception e) {
      logger.error(GET_POLINE_EXCEPTION + polineId, e);
      future.completeExceptionally(e);
    }

    return future;
  }
}
