package app.doqa.cukeng.steps;

import static org.testng.Assert.assertEquals;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class AccountSteps {

    public static final List<String> EXECUTED = new CopyOnWriteArrayList<>();

    private int balance;

    @Given("a balance of {int}")
    public void aBalanceOf(int amount) {
        balance = amount;
        EXECUTED.add("balance " + amount);
    }

    @When("{int} is deposited")
    public void deposited(int amount) {
        balance += amount;
        EXECUTED.add("deposit " + amount);
    }

    @When("{int} is withdrawn")
    public void withdrawn(int amount) {
        balance -= amount;
        EXECUTED.add("withdraw " + amount);
    }

    @Then("the balance is {int}")
    public void theBalanceIs(int expected) {
        assertEquals(balance, expected);
    }
}
