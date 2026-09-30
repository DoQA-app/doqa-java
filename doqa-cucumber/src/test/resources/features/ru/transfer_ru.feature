# language: ru
@ru
Функция: Перевод по-русски

  Предыстория:
    Дано an account with 100 EUR

  Сценарий: Простой перевод
    Когда I transfer 40 EUR
    Тогда the balance is 60 EUR

  Структура сценария: Перевод <amount>
    Когда I transfer <amount> EUR
    Тогда the balance is <left> EUR

    Примеры:
      | amount | left |
      | 1      | 99   |
