package br.ars.payment_service.dto;

public class CheckoutRequest {
    private String customerEmail;
    private Long amount;
    private String planName;

    // Getters e Setters
    public String getCustomerEmail() { return customerEmail; }
    public void setCustomerEmail(String email) { this.customerEmail = email; }

    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }

    public String getPlanName() { return planName; }
    public void setPlanName(String planName) { this.planName = planName; }
}
