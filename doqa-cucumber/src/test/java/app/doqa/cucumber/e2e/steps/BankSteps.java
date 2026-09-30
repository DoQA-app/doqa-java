package app.doqa.cucumber.e2e.steps;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class BankSteps {

    private int balance;
    private boolean rejected;

    @Given("an account with {int} EUR")
    public void anAccount(int amount) {
        balance = amount;
        Executed.add("account " + amount);
    }

    @When("I transfer {int} EUR")
    public void transfer(int amount) {
        balance -= amount;
        Executed.add("transfer " + amount);
    }

    @When("I try to transfer {int} EUR")
    public void tryTransfer(int amount) {
        rejected = amount > 100;
    }

    @Then("the balance is {int} EUR")
    public void balanceIs(int expected) {
        if (balance != expected) {
            throw new AssertionError("balance " + balance + " != " + expected);
        }
    }

    @Then("the transfer is rejected")
    public void rejected() {
        if (!rejected) {
            throw new AssertionError("not rejected");
        }
    }

    @Given("a document:")
    public void document(String text) {
        Executed.add("document " + text.length());
    }

    @Given("a table:")
    public void table(DataTable table) {
        Executed.add("table " + table.height());
    }
}
