package br.com.fiscal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.*;
import java.time.LocalDate;
import java.util.*;
import static br.com.fiscal.NfseSettings.bad;

/** Dados comerciais e escolhas explícitas do formulário; cálculos sempre refeitos no servidor. */
final class InvoiceForm {
    record Item(@NotNull @DecimalMin("0.0001") @Digits(integer=9,fraction=4) BigDecimal quantity,
        @NotNull @DecimalMin("0.01") @Digits(integer=12,fraction=2) BigDecimal unitPrice) {}
    record Tax(boolean enabled,@NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=2) BigDecimal rate,
        @NotNull @DecimalMin("0") @Digits(integer=15,fraction=2) BigDecimal base) {}
    record Taxes(@NotNull @Valid Tax iss,@NotNull @Valid Tax pis,@NotNull @Valid Tax cofins,
        @NotNull @Valid Tax csll,@NotNull @Valid Tax irrf,@NotNull @Valid Tax inss,
        @Pattern(regexp="00|01|02|04|05|06|07|08|09|49|99") String pisCofinsCst) {}
    record Approximation(@NotBlank @Pattern(regexp="NONE|SIMPLES|PERCENT") String mode,
        @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=2) BigDecimal simples,
        @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=2) BigDecimal federal,
        @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=2) BigDecimal state,
        @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=2) BigDecimal municipal) {}
    record Details(@NotNull @Valid NfseModels.Recipient recipient,@NotEmpty @Size(max=20) List<@NotNull @Valid Item> items,
        @NotBlank @Pattern(regexp="NONE|PIX|CASH|TRANSFER|CREDIT|DEBIT|OTHER") String paymentMethod,LocalDate dueDate,
        @NotNull @DecimalMin("0") @Digits(integer=15,fraction=2) BigDecimal unconditionalDiscount,
        @NotNull @DecimalMin("0") @Digits(integer=15,fraction=2) BigDecimal conditionalDiscount,
        @Size(max=1700) String complement,@NotNull @Valid Taxes taxes,@Valid Approximation approximation,
        @Pattern(regexp="[0-9]{7}|") String serviceMunicipality,@Pattern(regexp="[0-9]{3}|") String municipalServiceCode) {}
    record Totals(BigDecimal gross,BigDecimal discount,Map<String,BigDecimal> retentions,BigDecimal retained,BigDecimal estimatedNet) {}
    static BigDecimal money(BigDecimal value){return value.setScale(2,RoundingMode.HALF_UP);}
    static Map<String,Tax> taxes(Taxes values) {
        var result=new LinkedHashMap<String,Tax>();result.put("iss",values.iss());result.put("pis",values.pis());result.put("cofins",values.cofins());result.put("csll",values.csll());result.put("irrf",values.irrf());result.put("inss",values.inss());return result;
    }
    static Totals calculate(Details input) {
        if(!NationalXml.validTaxId(input.recipient().taxId()))throw bad("CPF/CNPJ do tomador inválido");
        BigDecimal gross=input.items().stream().map(i->money(i.quantity().multiply(i.unitPrice()))).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal discount=input.unconditionalDiscount().add(input.conditionalDiscount());
        if(gross.signum()<=0||discount.compareTo(gross)>0)throw bad("Descontos não podem exceder o valor dos serviços");
        BigDecimal issBase=gross.subtract(input.unconditionalDiscount());
        var values=new LinkedHashMap<String,BigDecimal>();
        for(var entry:taxes(input.taxes()).entrySet()) {
            var tax=entry.getValue();
            if(tax.enabled()&&(tax.rate().signum()<=0||tax.base().signum()<=0))throw bad("Informe base e alíquota maiores que zero para "+entry.getKey().toUpperCase(Locale.ROOT));
            if(tax.base().compareTo(gross)>0)throw bad("A base de retenção não pode exceder o valor dos serviços neste formulário");
            if(entry.getKey().equals("iss")&&tax.base().compareTo(issBase)!=0)throw bad("A base de ISS deste formulário deve corresponder aos serviços menos o desconto incondicionado");
            values.put(entry.getKey(),tax.enabled()?money(tax.base().multiply(tax.rate()).movePointLeft(2)):money(BigDecimal.ZERO));
        }
        if(input.taxes().pis().enabled()||input.taxes().cofins().enabled()) {
            if(input.taxes().pisCofinsCst()==null)throw bad("Informe o CST de PIS/COFINS");
            if(input.taxes().pis().enabled()&&input.taxes().cofins().enabled()&&input.taxes().pis().base().compareTo(input.taxes().cofins().base())!=0)throw bad("PIS e COFINS precisam compartilhar a mesma base neste formulário");
        }
        BigDecimal retained=values.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal net=gross.subtract(discount).subtract(retained);
        if(net.signum()<0)throw bad("Descontos e retenções excedem o valor dos serviços");
        if(input.approximation()!=null)totalTaxes(input.approximation());
        return new Totals(money(gross),money(discount),values,money(retained),money(net));
    }
    static BigDecimal selected(Tax tax){return tax.enabled()?tax.rate():BigDecimal.ZERO;}
    static FiscalApi.DefaultsInput applied(FiscalApi.DefaultsInput original,Details input) {
        var t=input.taxes();
        return new FiscalApi.DefaultsInput(original.effectiveFrom(),original.lc116(),original.nationalService(),original.cnae(),original.description(),
            t.iss().rate(),selected(t.irrf()),selected(t.pis()),selected(t.cofins()),selected(t.csll()),selected(t.inss()),t.iss().enabled(),original.variantName(),original.nbs());
    }
    static boolean changed(FiscalApi.DefaultsInput original,FiscalApi.DefaultsInput selected) {
        return !original.issWithheld().equals(selected.issWithheld())||original.issRate().compareTo(selected.issRate())!=0||original.irrfRate().compareTo(selected.irrfRate())!=0||original.pisRate().compareTo(selected.pisRate())!=0||original.cofinsRate().compareTo(selected.cofinsRate())!=0||original.csllRate().compareTo(selected.csllRate())!=0||original.inssRate().compareTo(selected.inssRate())!=0;
    }
    static String itemDescription(String base,Details input) {
        StringBuilder text=new StringBuilder(base.replaceAll("[\\r\\n\\t]+"," ").strip());
        for(var item:input.items())text.append(" | ").append(item.quantity().stripTrailingZeros().toPlainString()).append(" x R$ ").append(money(item.unitPrice()).toPlainString()).append(" = R$ ").append(money(item.quantity().multiply(item.unitPrice())).toPlainString());
        if(text.length()>2000)throw bad("A descrição e os itens excedem 2.000 caracteres");
        return text.toString();
    }
    static String complement(Details input) {
        String text=input.complement()==null?"":input.complement().strip();
        String payment=switch(input.paymentMethod()){case "PIX"->"PIX";case "CASH"->"Dinheiro";case "TRANSFER"->"Transferência";case "CREDIT"->"Cartão de crédito";case "DEBIT"->"Cartão de débito";case "OTHER"->"Outros";default->"";};
        if(!payment.isEmpty())text+=(text.isEmpty()?"":"\n")+"Forma de pagamento: "+payment+".";
        if(input.dueDate()!=null)text+=(text.isEmpty()?"":"\n")+"Vencimento: "+input.dueDate()+".";
        return text.replaceAll("[\\r\\n\\t]+"," ").strip();
    }
    private static String tag(String key,BigDecimal value){return "<"+key+">"+value.toPlainString()+"</"+key+">";}
    static String federal(Details input,Totals values) {
        var t=input.taxes();var body=new StringBuilder();
        boolean pis=t.pis().enabled(),cofins=t.cofins().enabled(),csll=t.csll().enabled();
        if(pis||cofins) {
            int retention=pis?(cofins?(csll?3:4):(csll?9:5)):(csll?7:6);
            body.append("<piscofins><CST>").append(t.pisCofinsCst()).append("</CST>").append(tag("vBCPisCofins",money(pis?t.pis().base():t.cofins().base()))).append(tag("pAliqPis",money(selected(t.pis())))).append(tag("pAliqCofins",money(selected(t.cofins())))).append(tag("vPis",values.retentions().get("pis"))).append(tag("vCofins",values.retentions().get("cofins"))).append("<tpRetPisCofins>").append(retention).append("</tpRetPisCofins></piscofins>");
        }
        if(t.inss().enabled())body.append(tag("vRetCP",values.retentions().get("inss")));
        if(t.irrf().enabled())body.append(tag("vRetIRRF",values.retentions().get("irrf")));
        if(csll)body.append(tag("vRetCSLL",values.retentions().get("csll")));
        return body.isEmpty()?null:"<tribFed xmlns=\""+NationalXml.NS+"\">"+body+"</tribFed>";
    }
    static String totalTaxes(Approximation input) {
        String content=switch(input.mode()) {
            case "NONE"->"<indTotTrib>0</indTotTrib>";
            case "SIMPLES"->{if(input.simples()==null)throw bad("Informe o percentual aproximado do Simples");yield tag("pTotTribSN",money(input.simples()));}
            case "PERCENT"->{if(input.federal()==null||input.state()==null||input.municipal()==null)throw bad("Informe os percentuais aproximados federal, estadual e municipal");yield "<pTotTrib>"+tag("pTotTribFed",money(input.federal()))+tag("pTotTribEst",money(input.state()))+tag("pTotTribMun",money(input.municipal()))+"</pTotTrib>";}
            default->throw bad("Escolha como informar os tributos aproximados");
        };
        return "<totTrib xmlns=\""+NationalXml.NS+"\">"+content+"</totTrib>";
    }
}
