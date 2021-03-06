package com.greenaddress.greenbits;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by Antonio Parrella on 2/16/17.
 * by inbitcoin
 * see: https://github.com/bitcoin-wallet/bitcoin-wallet/blob/bd1d46f3024c59743e067c1848d119974d4dd52f/wallet/src/de/schildbach/wallet/util/Formats.java
 */

public class FormatMemo {

    final static Pattern PATTERN_MEMO = Pattern.compile(
            "(?:Payment request for Coinbase order code: (.+)|Payment request for BitPay invoice (.+) for merchant (.+))",
            Pattern.CASE_INSENSITIVE);

    public static String[] sanitizeMemo(final String memo) {
        if (memo == null)
            return null;

        if (memo.endsWith("‹inbitcoin")) {
            final String[] memoElements = memo.split(" ");
            if (memoElements.length > 2)
                return new String[] { memoElements[memoElements.length-2], "inbitcoin", memoElements[0]};
            else
                return null;
        }

        final Matcher m = PATTERN_MEMO.matcher(memo);
        if (m.matches() && m.group(1) != null)
            return new String[] { m.group(1), "Coinbase" };
        else if (m.matches() && m.group(2) != null)
            return new String[] { m.group(2), "BitPay", m.group(3) };
        else
            return new String[] { memo };
    }
}
