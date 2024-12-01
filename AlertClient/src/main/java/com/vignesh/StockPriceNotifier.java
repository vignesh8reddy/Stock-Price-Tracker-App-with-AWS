package com.vignesh;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.spec.PutItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec;
import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.AmazonSNSClientBuilder;
import com.amazonaws.services.sns.model.PublishRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

public class StockPriceNotifier implements RequestHandler<ScheduledEvent, String> {

    private static final String ALERTS_TABLE = "StockAlerts";
    private static final String PRICES_TABLE = "StockPrices";
    private static final String SNS_TOPIC_ARN = "arn:aws:sns:us-east-1:867344464221:StockPriceAlerts";
    private static final String ALPHA_VANTAGE_API_URL = "https://www.alphavantage.co/query";
    private static final String API_KEY = "ODNA5U2258JCA8M2";

    private final AmazonDynamoDB client = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(client);
    private final AmazonSNS snsClient = AmazonSNSClientBuilder.defaultClient();
    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String handleRequest(ScheduledEvent event, Context context) {
        try {
            Table alertsTable = dynamoDB.getTable(ALERTS_TABLE);
            Table pricesTable = dynamoDB.getTable(PRICES_TABLE);

            // Fetch and store the latest stock prices
            fetchAndStoreStockPrices(alertsTable, pricesTable);

            // Fetch the latest stock prices for all stocks
            Map<String, Double> stockPrices = new HashMap<>();
            fetchLatestStockPrices(pricesTable, stockPrices);

            // Scan the alerts table for all active alerts
            ItemCollection<ScanOutcome> alertItems = alertsTable.scan(new ScanSpec());

            // Process each alert
            alertItems.forEach(alertItem -> {
                String stockSymbol = alertItem.getString("StockSymbol");
                String alertStatus = alertItem.getString("AlertStatus");
                double targetPrice = alertItem.getDouble("TargetPrice");

                if ("ON".equalsIgnoreCase(alertStatus)) {
                    // Check if the stock symbol exists in the fetched stock prices map
                    if (stockPrices.containsKey(stockSymbol)) {
                        double currentPrice = stockPrices.get(stockSymbol);

                        // Check if the current price exceeds the threshold
                        if (currentPrice > targetPrice) {
                            // Send SNS notification
                            String message = String.format("Alert! %s has reached $%.2f, exceeding the target price of $%.2f.",
                                    stockSymbol, currentPrice, targetPrice);
                            sendNotification(message);
                        }
                    } else {
                        context.getLogger().log("No price data found for StockSymbol: " + stockSymbol);
                    }
                }
            });

            return "Alerts processed successfully.";
        } catch (Exception e) {
            context.getLogger().log("Error processing alerts: " + e.getMessage());
            return "Error processing alerts.";
        }
    }

    private void fetchAndStoreStockPrices(Table alertsTable, Table pricesTable) throws Exception {
        // Scan the alerts table to get all active symbols
        ItemCollection<ScanOutcome> alertItems = alertsTable.scan(new ScanSpec());
        alertItems.forEach(alertItem -> {
            String stockSymbol = alertItem.getString("StockSymbol");
            try {
                double price = fetchStockPrice(stockSymbol);
                storeStockPriceInDynamoDB(pricesTable, stockSymbol, price, String.valueOf(System.currentTimeMillis()));
            } catch (Exception e) {
                System.err.println("Error fetching or storing price for stock: " + stockSymbol + ". Error: " + e.getMessage());
            }
        });
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
    private void storeStockPriceInDynamoDB(Table pricesTable, String stockSymbol, double price, String timestamp) {
        try {
            Item item = new Item()
                    .withPrimaryKey("StockSymbol", stockSymbol, "Timestamp", timestamp)
                    .withNumber("Price", price);
            pricesTable.putItem(new PutItemSpec().withItem(item));
        } catch (Exception e) {
            System.err.println("Error storing price in DynamoDB for stock: " + stockSymbol + ". Error: " + e.getMessage());
        }
    }

    private void fetchLatestStockPrices(Table pricesTable, Map<String, Double> stockPrices) {
        // Query for the most recent stock price for each symbol
        ScanSpec scanSpec = new ScanSpec();
        ItemCollection<ScanOutcome> priceItems = pricesTable.scan(scanSpec);

        priceItems.forEach(priceItem -> {
            String stockSymbol = priceItem.getString("StockSymbol");
            Double currentPrice = priceItem.getDouble("Price");

            // Ensure we're getting the latest price for each symbol
            if (stockSymbol != null && currentPrice != null) {
                stockPrices.put(stockSymbol, currentPrice);
            }
        });
    }

    private void sendNotification(String message) {
        PublishRequest publishRequest = new PublishRequest()
                .withTopicArn(SNS_TOPIC_ARN)
                .withMessage(message);
        snsClient.publish(publishRequest);
    }
}
