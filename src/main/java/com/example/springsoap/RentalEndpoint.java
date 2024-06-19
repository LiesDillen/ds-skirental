package com.example.springsoap;

import io.skirental.gt.webservice.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import com.example.springsoap.exception.*;
import org.springframework.ws.soap.SoapFaultException;

import java.io.IOException;
import java.util.List;


@Endpoint
public class RentalEndpoint
{
    private static final String NAMESPACE_URI = "http://skirental.io/gt/webservice";

    private final MaterialRepository materialrepo;

    @Autowired
    public RentalEndpoint(MaterialRepository materialrepo) {
        this.materialrepo = materialrepo;
    }

    @PayloadRoot(namespace = NAMESPACE_URI, localPart = "getMaterialByIdRequest")
    @ResponsePayload
    public GetMaterialByIdResponse getMaterialByIdResponse(@RequestPayload GetMaterialByIdRequest request)
    {
        GetMaterialByIdResponse response = new GetMaterialByIdResponse();
        response.setMaterial(materialrepo.findMaterialById(request.getId()));

        return response;
    }

    @PayloadRoot(namespace = NAMESPACE_URI, localPart = "getStockRequest")
    @ResponsePayload
    public GetStockResponse getStockResponse(@RequestPayload GetStockRequest request)
    {
        GetStockResponse response = new GetStockResponse();
        List<Material> stockList = materialrepo.getFullStock();
        response.getStock().addAll(stockList);

        return response;
    }

    @PayloadRoot(namespace = NAMESPACE_URI, localPart = "prepareRequest")
    @ResponsePayload
    public PrepareResponse prepare(@RequestPayload PrepareRequest request)
    {
        PrepareResponse response = new PrepareResponse();
        try {
            Vote vote = materialrepo.sendVote(request.getTransactionId(), request.getOrders());
            response.setVote(vote);
        } catch (IOException e) {
            throw new SoapFaultException("Internal server error: " + e.getMessage());
        }
        return response;
    }

    @PayloadRoot(namespace = NAMESPACE_URI, localPart = "bookMaterialRequest")
    @ResponsePayload
    public BookMaterialResponse bookMaterial(@RequestPayload BookMaterialRequest request)
    {
        BookMaterialResponse response = new BookMaterialResponse();
        try {
            ProtocolMessage ack = materialrepo.bookItem(request.getTransactionId(), request.getDecision(), request.getOrders());
            response.setAck(ack);
        } catch (IOException e) {
            throw new SoapFaultException("Internal server error: " + e.getMessage());
        } catch (InsufficientStockException e) {
            throw new SoapFaultException("Insufficient stock: " + e.getMessage());
        } catch (InvalidStockIdException e) {
            throw new SoapFaultException("Invalid stock ID: " + e.getMessage());
        }
        return response;
    }

    @PayloadRoot(namespace = NAMESPACE_URI, localPart = "rollBackRequest")
    @ResponsePayload
    public void rollBack(@RequestPayload RollBackRequest request)
    {
        materialrepo.rollBack(request.getTransactionId());
    }
}