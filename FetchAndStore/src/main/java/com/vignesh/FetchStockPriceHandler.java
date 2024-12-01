package com.vignesh;

import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.PutItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class FetchStockPriceHandler {

    private static final DynamoDB dynamoDB = new DynamoDB(AmazonDynamoDBClientBuilder.defaultClient());
    private static final OkHttpClient httpClient = new OkHttpClient();
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String ALPHA_VANTAGE_API_URL = "https://www.alphavantage.co/query";
    private static final String API_KEY = "ODNA5U2258JCA8M2";
    private static final String DYNAMODB_TABLE_NAME = "StockPrices";

    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();

        // Set CORS headers
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type");
        response.setHeaders(headers);

        try {
            // Extract the stock symbol from the query parameters
            String stockSymbol = event.getQueryStringParameters().get("symbol");
            if (stockSymbol == null || stockSymbol.isEmpty()) {
                response.setStatusCode(400);
                response.setBody("{\"message\":\"Error: Stock symbol is required.\"}");
                return response;
            }

            // Fetch the current stock price
            double currentPrice = fetchStockPrice(stockSymbol);
            if (currentPrice == -1.0) {
                response.setStatusCode(500);
                response.setBody("{\"message\":\"Error: Could not fetch stock price for symbol: " + stockSymbol + "\"}");
                return response;
            }

            // Store the current price in the database
            String timestamp = Instant.now().toString();
            storeStockPriceInDynamoDB(stockSymbol, currentPrice, timestamp);

            // Query historical prices from the database
            Map<String, Object> historicalData = queryHistoricalPricesFromDynamoDB(stockSymbol);

            // Create the response JSON
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("currentPrice", currentPrice);
            responseBody.put("timestamp", timestamp);
            responseBody.put("historicalData", historicalData);

            response.setStatusCode(200);
            response.setBody(objectMapper.writeValueAsString(responseBody));
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatusCode(500);
            response.setBody("{\"message\":\"Error occurred: " + e.getMessage() + "\"}");
        }

        return response;
    }

    // Fetches the stock price from Alpha Vantage API
    private double fetchStockPrice(String stockSymbol) throws Exception {
        String url = String.format("%s?function=TIME_SERIES_INTRADAY&symbol=%s&interval=1min&apikey=%s",
                ALPHA_VANTAGE_API_URL, stockSymbol, API_KEY);
        Request request = new Request.Builder().url(url).build();
        Response response = httpClient.newCall(request).execute();

        if (!response.isSuccessful()) {
            throw new Exception("Failed to fetch stock price.");
        }

        String responseBody = response.body().string();
        JsonNode rootNode = objectMapper.readTree(responseBody);
        JsonNode timeSeriesNode = rootNode.path("Time Series (1min)");

        if (timeSeriesNode.isMissingNode()) {
            return -1.0;
        }

        JsonNode latestData = timeSeriesNode.fields().next().getValue();
        JsonNode closePriceNode = latestData.path("4. close");

        return closePriceNode.isMissingNode() ? -1.0 : closePriceNode.asDouble();
    }

    // Stores the stock price in the DynamoDB table
    private void storeStockPriceInDynamoDB(String stockSymbol, double price, String timestamp) {
        Table table = dynamoDB.getTable(DYNAMODB_TABLE_NAME);
        Item item = new Item()
                .withPrimaryKey("StockSymbol", stockSymbol, "Timestamp", timestamp)
                .withNumber("Price", price);
        table.putItem(new PutItemSpec().withItem(item));
    }

    // Queries historical prices from the DynamoDB table
    private Map<String, Object> queryHistoricalPricesFromDynamoDB(String stockSymbol) {
        Table table = dynamoDB.getTable(DYNAMODB_TABLE_NAME);
        QuerySpec querySpec = new QuerySpec().withHashKey("StockSymbol", stockSymbol);

        Iterator<Item> items = table.query(querySpec).iterator();
        Map<String, Object> historicalData = new HashMap<>();
        while (items.hasNext()) {
            Item item = items.next();
            historicalData.put(item.getString("Timestamp"), item.getDouble("Price"));
        }
        return historicalData;
    }
}
