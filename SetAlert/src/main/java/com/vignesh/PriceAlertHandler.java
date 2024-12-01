package com.vignesh;

import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.PutItemSpec;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class PriceAlertHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String ALERTS_TABLE = "StockAlerts";
    private final AmazonDynamoDB client = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(client);

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type");

        response.setHeaders(headers);

        try {
            JSONObject body = new JSONObject(request.getBody());
            String stockSymbol = body.getString("symbol");
            boolean alertStatus = body.getBoolean("alertStatus");
            double targetPrice = body.getDouble("targetPrice");

            Table table = dynamoDB.getTable(ALERTS_TABLE);

            // Convert boolean alertStatus to String ("ON"/"OFF")
            String alertStatusString = alertStatus ? "ON" : "OFF";

            // Add or update alert in DynamoDB
            Item item = new Item()
                    .withPrimaryKey("StockSymbol", stockSymbol)
                    .withString("AlertStatus", alertStatusString)
                    .withNumber("TargetPrice", targetPrice)
                    .withNumber("Timestamp", System.currentTimeMillis());
            table.putItem(new PutItemSpec().withItem(item));

            response.setStatusCode(200);
            response.setBody("{\"message\": \"Alert updated successfully.\"}");
        } catch (Exception e) {
            response.setStatusCode(500);
            response.setBody("{\"error\": \"" + e.getMessage() + "\"}");
        }

        return response;
    }
}
