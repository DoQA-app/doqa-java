@bank @doqa.case:10
Feature: Money transfer
  Transfers between the accounts of one customer.

  Background:
    Given an account with 100 EUR

  Scenario: Simple transfer
    When I transfer 30 EUR
    Then the balance is 70 EUR

  @outline @Doqa.Case=11
  Scenario Outline: Transfer <amount> to <who>
    When I transfer <amount> EUR
    Then the balance is <left> EUR

    @first @doqa.id:transfer-{who}
    Examples: First block
      | amount | who   | left |
      # a comment inside the table
      | 10     | alice | 90   |

      | 20     | bob   | 80   |

    Examples: Second block
      | amount | who     | left |
      | 5      | eve\|x  | 95   |

  Scenario: Documents
    Given a document:
      """
      hello
      world
      """
    And a table:
      | a | b |
      | 1 | 2 |

  Rule: Limits

    @DOQA-501
    Scenario: Over the limit
      When I try to transfer 500 EUR
      Then the transfer is rejected

    Scenario: [DOQA-502] Within the limit
      When I transfer 50 EUR
      Then the balance is 50 EUR
