Feature: Spaced path
  A feature in a directory whose name needs percent-encoding in a URI.

  Scenario: Transfer from a spaced path
    Given an account with 100 EUR
    When I transfer 30 EUR
    Then the balance is 70 EUR

  Scenario Outline: Spaced transfer <amount>
    Given an account with 100 EUR
    When I transfer <amount> EUR
    Then the balance is <left> EUR

    Examples:
      | amount | left |
      | 10     | 90   |
