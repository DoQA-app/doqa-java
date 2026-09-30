Feature: Joint account

  Scenario: Deposit
    Given a balance of 10
    When 5 is deposited
    Then the balance is 15

  Scenario Outline: Withdraw <amount>
    Given a balance of 10
    When <amount> is withdrawn
    Then the balance is <left>

    Examples:
      | amount | left |
      | 3      | 7    |
      | 4      | 6    |
