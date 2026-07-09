---
type: Article
title: Domain Overview
tags: [shop, overview]
---

# Domain Overview

This article introduces the small e-commerce domain used across this demo workspace.

A customer places an order. An order contains one or more order items, each referencing
a product and a quantity. The order moves through a checkout state machine from
`Draft` to `Confirmed` to `Shipped`, and may be `Cancelled` at various points.

For the underlying class model see [Domain Classes](../models/domain-classes.md).
For the checkout lifecycle see [Checkout State Machine](../models/checkout-state.md).
