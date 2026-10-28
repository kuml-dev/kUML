sysml2Model("OrderHandling") {
    val customer = activityPartition("Customer")
    val shop = activityPartition("Shop")

    val start = initialNode(partition = customer)
    val placeOrder =
        actionDef(
            name = "PlaceOrder",
            partition = customer,
            pins =
                listOf(
                    ActionPin(name = "order", typeId = "Order", direction = PinDirection.Output, side = PinSide.Bottom),
                    ActionPin(name = "budget", typeId = "Money", direction = PinDirection.Output, side = PinSide.Bottom),
                ),
        )
    val stock = callBehavior(name = "CheckStock", activityId = "StockCheckActivity", partition = shop)
    val inStock = decisionNode(name = "in stock?", partition = shop, showName = true)

    val payment = interruptibleRegion(name = "Payment")
    val retry = interruptibleRegion(name = "Retry", parent = payment)
    val requestPayment = sendSignal(name = "RequestPayment", signal = "PaymentRequest", partition = shop, region = payment)
    val paid = acceptEvent(name = "PaymentReceived", partition = shop, region = payment)
    val timeout = acceptTimeEvent(name = "PaymentTimeout", time = "after 10 min", partition = shop, region = payment)
    val charge =
        actionDef(
            name = "ChargeCard",
            partition = shop,
            region = payment,
            pins =
                listOf(
                    ActionPin(name = "order", typeId = "Order", direction = PinDirection.Input, side = PinSide.Top),
                ),
        )
    val retryCharge = actionDef(name = "RetryCharge", partition = shop, region = retry)
    val handleError = actionDef(name = "HandleError", partition = shop)
    val cancel = actionDef(name = "CancelOrder", partition = shop)

    val split = forkNode(name = "pack and notify", partition = shop, showName = true)
    val pack = actionDef(name = "PackGoods", partition = shop)
    val notify = actionDef(name = "NotifyCustomer", partition = customer)
    val join = joinNode(name = "done", partition = shop, showName = true)
    val end = finalNode(partition = shop)
    val aborted = flowFinalNode(partition = shop)

    controlFlow(name = "start", source = start, target = placeOrder)
    objectFlow(name = "order", source = placeOrder, target = charge, objectType = "Order", sourcePin = "order", targetPin = "order")
    controlFlow(name = "toStock", source = placeOrder, target = stock)
    controlFlow(name = "stockChecked", source = stock, target = inStock)
    controlFlow(name = "yes", source = inStock, target = requestPayment, guard = "yes")
    controlFlow(name = "no", source = inStock, target = cancel, guard = "no")
    controlFlow(name = "waiting", source = requestPayment, target = paid)
    controlFlow(name = "received", source = paid, target = charge)
    controlFlow(name = "again", source = charge, target = retryCharge)
    controlFlow(name = "timedOut", source = timeout, target = cancel, interrupts = true)
    controlFlow(name = "afterCharge", source = charge, target = split)
    controlFlow(name = "packing", source = split, target = pack)
    controlFlow(name = "informing", source = split, target = notify)
    controlFlow(name = "packed", source = pack, target = join)
    controlFlow(name = "informed", source = notify, target = join)
    controlFlow(name = "finish", source = join, target = end)
    controlFlow(name = "abort", source = cancel, target = aborted)
    exceptionHandler(protectedNode = charge, handler = handleError, type = "PaymentError")
    controlFlow(name = "errorEnd", source = handleError, target = aborted)

    actDiagram("Order handling") {
        include(start)
        include(placeOrder)
        include(stock)
        include(inStock)
        include(requestPayment)
        include(paid)
        include(timeout)
        include(charge)
        include(retryCharge)
        include(handleError)
        include(cancel)
        include(split)
        include(pack)
        include(notify)
        include(join)
        include(end)
        include(aborted)
    }
}
